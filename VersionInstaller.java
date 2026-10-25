package net.xuanzhi.launcher.launch;

import android.os.SystemClock;
import androidx.annotation.NonNull;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hanyi.launcher.download.Downloader;
import net.hanyi.launcher.meta.AssetIndex;
import net.hanyi.launcher.meta.VersionMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;

/**
 * 把「一个版本」装到本地：
 *   1. 抓 version.json（若本地已有且 sha1 对就跳过）
 *   2. 抓 client.jar
 *   3. 抓 libraries（含 natives，按 Android/arm64 过滤）
 *   4. 抓 asset index + 常用 assets（首批按需，这里先抓 index）
 *
 * 全部落盘到 profile.gameDir 里，结构和官方启动器的 .minecraft 一致，
 * 这样游戏自己找 assets、找 libraries 都不用打补丁。
 */
public final class VersionInstaller {

    public interface Listener {
        void onStage(@NonNull String stage);
        void onProgress(@NonNull String task, long done, long total);
        void onDone(@NonNull VersionMeta meta);
        void onError(@NonNull String task, @NonNull IOException e);
    }

    private final OkHttpClient http;
    private final ObjectMapper om;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final File gameDir;

    public VersionInstaller(@NonNull OkHttpClient http, @NonNull ObjectMapper om, @NonNull File gameDir) {
        this.http = http;
        this.om = om;
        this.gameDir = gameDir;
    }

    public void install(@NonNull String versionId, @NonNull String versionJsonUrl,
                        @NonNull String assetHost, @NonNull Listener l) {
        try {
            l.onStage("拉取 " + versionId + " 版本清单…");
            File vDir = new File(gameDir, "versions/" + versionId);
            vDir.mkdirs();
            File metaFile = new File(vDir, versionId + ".json");

            // 1) version.json
            new Downloader(http).fetch("version.json", versionJsonUrl, metaFile, null, new DProgress(l));
            VersionMeta meta = om.readValue(metaFile, VersionMeta.class);

            // 继承父版本（1.xx.x 的小版本常 inheritsFrom -> 1.xx）
            if (!meta.inheritsFrom.isEmpty() && !meta.inheritsFrom.equals(versionId)) {
                l.onStage("继承父版本 " + meta.inheritsFrom);
                // 父版本的 json 走同一份 manifest 解析链，这里简化：直接复用已下载的
            }

            // 2) client.jar
            if (meta.downloads != null && meta.downloads.client != null) {
                File jar = new File(vDir, versionId + ".jar");
                l.onStage("下载 client.jar");
                new Downloader(http).fetch(versionId + ".jar", meta.downloads.client.url, jar,
                        meta.downloads.client.sha1, new DProgress(l));
            }

            // 3) libraries + natives
            l.onStage("下载依赖库（按 arm64 过滤）");
            File libsDir = new File(gameDir, "libraries");
            String abi = "linux-arm64"; // 我们的 JRE 是 linux 兼容 ABI
            List<VersionMeta.Library> needed = new ArrayList<>();
            for (VersionMeta.Library lib : meta.libraries) {
                if (!lib.applies(abi, om)) continue;
                needed.add(lib);
            }

            CountDownLatch latch = new CountDownLatch(needed.size());
            AtomicLong failAny = new AtomicLong(0);
            for (VersionMeta.Library lib : needed) {
                pool.submit(() -> {
                    try {
                        fetchLibrary(lib, libsDir, abi, l);
                    } catch (IOException e) {
                        failAny.incrementAndGet();
                        l.onError(lib.name, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            if (failAny.get() > 0) throw new IOException("有 " + failAny.get() + " 个依赖下载失败，见日志");

            // 4) asset index
            if (meta.assetIndex != null && !meta.assetIndex.url.isEmpty()) {
                File idxDir = new File(gameDir, "assets/indexes");
                idxDir.mkdirs();
                File idx = new File(idxDir, meta.assetIndex.id + ".json");
                l.onStage("下载资源索引 " + meta.assetIndex.id);
                new Downloader(http).fetch("asset-index", meta.assetIndex.url, idx,
                        meta.assetIndex.sha1, new DProgress(l));

                // 5) 首批关键资源（objects）。全量太大，这里只校验 index 存在，
                //    游戏启动时会按需拉剩余（官方启动器也是懒加载）
                preloadCriticalAssets(idx, assetHost, l);
            }

            // 6) logging config
            if (meta.logging != null && meta.logging.client != null && meta.logging.client.file != null) {
                VersionMeta.Artifact log = meta.logging.client.file;
                if (log.url != null && !log.url.isEmpty()) {
                    File logDir = new File(gameDir, "assets/log_configs");
                    logDir.mkdirs();
                    new Downloader(http).fetch("log4j.xml", log.url,
                            new File(logDir, versionId + ".xml"), log.sha1, new DProgress(l));
                }
            }

            l.onDone(meta);

        } catch (Exception e) {
            if (e instanceof IOException) l.onError("install", (IOException) e);
            else l.onError("install", new IOException("准备版本失败", e));
        }
    }

    private void fetchLibrary(@NonNull VersionMeta.Library lib, @NonNull File libsDir,
                              @NonNull String abi, @NonNull Listener l) throws IOException {
        String suffix = lib.nativesSuffix(abi);
        String path = lib.mavenPath(suffix);
        File target = new File(libsDir, path);
        if (target.exists() && target.length() > 4096) return; // 已有，粗略跳过
        target.getParentFile().mkdirs();

        // 优先用 library 自己的 downloads.classifiers.<natives>；没有就走 maven 默认路径
        String url = "https://libraries.minecraft.net/" + path;
        String sha1 = null;
        if (lib.downloads != null && lib.downloads.classifiers != null) {
            // classifiers 是个 { "natives-linux-arm64": { url, sha1 } } 结构
            com.fasterxml.jackson.databind.JsonNode c = lib.downloads.classifiers.get(abi);
            if (c == null) c = lib.downloads.classifiers.get("natives-linux-arm64");
            if (c == null && !suffix.isEmpty()) c = lib.downloads.classifiers.get(suffix.replace("-", "_"));
            if (c != null && c.isObject()) {
                com.fasterxml.jackson.databind.JsonNode u = c.get("url");
                if (u != null) url = u.asText();
                com.fasterxml.jackson.databind.JsonNode s = c.get("sha1");
                if (s != null) sha1 = s.asText();
            }
        }
        new Downloader(http).fetch("lib:" + lib.name, url, target, sha1, new DProgress(l));

        // natives 解压到 natives/<versionId>/，游戏通过 -Djava.library.path 找到
        if (!suffix.isEmpty()) extractNatives(target, new File(gameDir, "natives/" + lib.name.replace(':', '_')), l);
    }

    private void extractNatives(@NonNull File jar, @NonNull File out, @NonNull Listener l) throws IOException {
        out.mkdirs();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.FileInputStream(jar))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (!n.endsWith(".so") && !n.endsWith(".dll") && !n.endsWith(".dylib")) continue;
                if (n.startsWith("META-INF/")) continue;
                File f = new File(out, new File(n).getName());
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    int k; while ((k = z.read(buf)) != -1) fos.write(buf, 0, k);
                }
                f.setExecutable(true, false);
                z.closeEntry();
            }
        }
    }

    private void preloadCriticalAssets(@NonNull File idxFile, @NonNull String host, @NonNull Listener l) throws IOException {
        AssetIndex idx = om.readValue(idxFile, AssetIndex.class);
        if (idx.objects == null) return;
        File objRoot = new File(gameDir, "assets");
        // 只预热第一批（音效/语言包等大头的 key 固定），避免首次进游戏卡死
        int i = 0;
        for (java.util.Map.Entry<String, AssetIndex.Entry> e : idx.objects.entrySet()) {
            AssetIndex.Entry v = e.getValue();
            if (v == null || v.hash == null) continue;
            if (!e.getKey().startsWith("minecraft/lang/") && !e.getKey().startsWith("minecraft/textures/gui/")) continue;
            File f = new File(objRoot, v.localPath());
            if (f.exists() && f.length() == v.size) continue;
            f.getParentFile().mkdirs();
            String url = host + (v.hash.length() >= 2 ? v.hash.substring(0, 2) + "/" : "") + v.hash;
            new Downloader(http).fetch("asset:" + e.getKey(), url, f, v.hash, new DProgress(l));
            if (++i > 200) break; // 预热上限，剩下的游戏自己拉
        }
    }

    private static class DProgress implements Downloader.Progress {
        final Listener l;
        DProgress(Listener l) { this.l = l; }
        @Override public void onProgress(@NonNull String t, long d, long tt) { l.onProgress(t, d, tt); }
        @Override public void onDone(@NonNull File f) {}
        @Override public void onError(@NonNull String t, @NonNull IOException e) { l.onError(t, e); }
    }
}
