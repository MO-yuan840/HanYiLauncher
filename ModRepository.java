package net.xuanzhi.launcher.mods;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.hanyi.launcher.profile.LaunchProfile;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * 模组仓库门面：双源并发 + 本地索引兜底 + 已装去重 + 前置链自动展开。
 * <p>
 * 搜索策略（解决「网上有却搜不到」）：
 *   1. 两源同时飞出去（Modrinth 免 Key，CF 有 Key 才发），谁先回就用谁的排序，另一份插后面；
 *   2. 用 (title slug 归一) 去重，CF/MR 同一个模组只留一条、标两个源都可用；
 *   3. 网络全挂 → 退化为本地索引模糊匹配，至少不会白屏空结果。
 * <p>
 * 安装策略：resolveDeps 先算整棵依赖树，再按 installOrder 顺序下，
 * 已装过的（按 projectId/cfModId + 版本）直接跳过。
 */
public final class ModRepository {

    private static final int TIMEOUT_SEC = 14;

    private final ModrinthClient mr;
    private final CurseForgeClient cf;
    private final ExecutorService exec =
            Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "xuanzhi-mod-io"); t.setDaemon(true); return t; });

    public ModRepository(@NonNull Context ctx) {
        this.mr = new ModrinthClient(ctx);
        this.cf = new CurseForgeClient(ctx);
    }

    /* -------------------- 搜索 -------------------- */

    public CompletableFuture<List<ModModels.ModStub>> search(@Nullable String query,
                                                             @Nullable String mc,
                                                             @Nullable String loader) {
        List<CompletableFuture<List<ModModels.ModStub>>> futs = new ArrayList<>();
        futs.add(supply(() -> mr.search(query, mc, loader, 60)));
        if (cf.available()) futs.add(supply(() -> cf.search(query, mc, loader, 40)));

        return CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]))
                .orTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .handle((v, ex) -> {
                    List<ModModels.ModStub> merged = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    for (CompletableFuture<List<ModModels.ModStub>> f : futs) {
                        List<ModModels.ModStub> list = safeJoin(f);
                        for (ModModels.ModStub s : list) {
                            String k = normalize(s.title);
                            if (k.isEmpty()) k = s.slug + s.projectId;
                            if (seen.add(k)) {
                                merged.add(s);
                            } else {
                                // 同一个模组两个源都有 → 合并源标记，下载时可择优
                                for (ModModels.ModStub m : merged)
                                    if (normalize(m.title).equals(k) && !m.source.contains(s.source))
                                        m.source = m.source + "+" + s.source;
                            }
                        }
                    }
                    // 兜底：本地索引补几条，保证永远有结果
                    if (merged.isEmpty() && query != null && !query.isEmpty()) {
                        List<ModModels.ModStub> local = mr.searchLocal(query, 30);
                        for (ModModels.ModStub s : local) if (s.matches(mc, loader)) merged.add(s);
                    }
                    return merged;
                });
    }

    /* -------------------- 依赖解析（装前必调） -------------------- */

    public ModModels.DepGraph resolve(@NonNull String source, @NonNull String projectId,
                                     @Nullable String versionId, @NonNull LaunchProfile profile) {
        ModrinthClient.ProgressSink noop = p -> {};
        try {
            tag(v, source);
        ModVersionFetcher fetcher = fetcher(source);
            ModModels.ModVersion root = fetcher.fetch(projectId, versionId, profile);
            return mr.resolveDeps(root, profile.getGameVersion(), profile.getLoader());
        } catch (Exception e) {
            ModModels.DepGraph g = new ModModels.DepGraph(new ModModels.ModVersion());
            g.unresolved.add(new ModModels.DepEdge()); // 标记失败
            return g;
        }
    }

    /* -------------------- 安装一个依赖图 -------------------- */

    public List<File> install(@NonNull ModModels.DepGraph graph, @NonNull LaunchProfile profile,
                              @NonNull ModrinthClient.ProgressSink sink) throws Exception {
        File modsDir = profile.getModsDir();
        //noinspection ResultOfMethodCallIgnored
        modsDir.mkdirs();
        List<File> installed = new ArrayList<>();
        List<ModModels.ModVersion> order = graph.installOrder();
        int total = order.size();
        for (int i = 0; i < total; i++) {
            ModModels.ModVersion v = order.get(i);
            final int step = i;
            File got = installOne(v, modsDir, p -> sink.on((step * 100 + p) / total));
            if (got != null) installed.add(got);
        }
        return installed;
    }

    /** 对外：装单个版本，已装跳过。给 ViewModel 按依赖序循环调用。 */
    @Nullable
    public File installOneSafe(ModModels.ModVersion v, @NonNull LaunchProfile profile,
                             @Nullable ModrinthClient.ProgressSink sink) throws Exception {
        if (v == null) return null;
        File modsDir = profile.getModsDir();
        File target = new File(modsDir, v.primaryFile().filename);
        if (target.exists() && target.length() > 0) { if (sink != null) sink.on(100); return target; }
        return installOne(v, modsDir, sink);
    }

    /** 把搜索命中的模组记入本地索引，离线也能搜到（搜不到问题的最后一道保险） */
    public void cache(ModModels.ModStub s) { mr.mergeIntoLocalIndex(s); }

    /** 关闭时清理线程池 */
    public void close() { exec.shutdownNow(); }

    @Nullable
    private File installOne(ModModels.ModVersion v, File modsDir,
                            ModrinthClient.ProgressSink sink) throws Exception {
        // 已装同版本跳过
        File target = new File(modsDir, v.primaryFile().filename);
        if (target.exists() && target.length() > 0) { sink.on(100); return target; }
        if ("curseforge".equals(TAG.get(v)))
            return cf.download(v, modsDir, sink);
        return mr.download(v, modsDir, sink);
    }

    /* -------------------- 内部 -------------------- */

    private ModVersionFetcher fetcher(String source) {
        return (proj, ver, p) -> {
            if ("curseforge".equals(source)) {
                ModModels.ModVersion v = cf.pickVersion(Long.parseLong(proj), p.getGameVersion(), p.getLoader());
                if (v == null) throw new IllegalStateException("CurseForge 无匹配版本: " + proj);
                tag(v, "curseforge");
                return v;
            }
            ModModels.ModVersion v = mr.versions(proj, p.getGameVersion(), p.getLoader()).stream()
                    .filter(x -> ver == null || ver.isEmpty() || x.id.equals(ver)).findFirst()
                    .orElseGet(() -> { try { return mr.versions(proj, null, p.getLoader()).get(0); }
                                       catch (Exception e) { throw new RuntimeException(e); } });
            tag(v, "modrinth");
            return v;
            List<ModModels.ModVersion> vs = mr.versions(proj, p.getGameVersion(), p.getLoader());
            if (vs.isEmpty()) vs = mr.versions(proj, null, p.getLoader());
            if (vs.isEmpty()) throw new IllegalStateException("Modrinth 无匹配版本: " + proj);
            if (ver == null || ver.isEmpty()) return vs.get(0);
            for (ModModels.ModVersion v : vs) if (v.id.equals(ver)) return v;
            return vs.get(0);
        };
    }

    @FunctionalInterface
    private interface ModVersionFetcher {
        ModModels.ModVersion fetch(String projectId, @Nullable String versionId, LaunchProfile p) throws Exception;
    }

    private static <T> CompletableFuture<T> supply(Callable<T> c) {
        return CompletableFuture.supplyAsync(() -> { try { return c.call(); } catch (Exception e) { return null; } }, exec);
    }

    @SuppressWarnings("unchecked")
    private static <T> T safeJoin(@NonNull CompletableFuture<T> f) {
        try { T v = f.getNow(null); return v != null ? v : (T) Collections.emptyList(); }
        catch (Exception e) { return (T) Collections.emptyList(); }
    }

    /** Modrinth 侧统一 source 标记（本地索引也靠它分流） */
    static String tag(ModModels.ModVersion v, String src) { ModRepository.TAG.put(v, src); return src; }
    static String detectSource(ModModels.ModVersion v) { String s = TAG.get(v); return s == null ? "modrinth" : s; }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** 给 ModVersion 加个临时的源标记，供 installOne 分流 */
    static String tag(ModModels.ModVersion v, String s) { TAG.put(v, s); return s; }
    static String detectSource(ModModels.ModVersion v) { String s = TAG.get(v); return s == null ? "modrinth" : s; }

    public static String tag(ModModels.ModVersion v, String s) { TAG.put(v, s); return s; }
}


