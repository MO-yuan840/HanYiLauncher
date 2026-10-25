package net.xuanzhi.launcher.mods;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.hanyi.launcher.util.HashUtil;
import okhttp3.OkHttpClient;
import net.hanyi.launcher.util.OkHttpCompat;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Modrinth 源。完全免 Key，可直接搜、可直接下。
 * 依赖字段见 version.files[].file_type / version.dependencies —— 官方 v2 返回
 * dependency_type = required / optional / incompatible / embeddedLibrary / tool
 * 我们只追 required，其余一律不动，避免把整个依赖树拉爆。
 */
public final class ModrinthClient {

    public static final String ID = "modrinth";
    private static final String API = "https://api.modrinth.com/v2";
    private static final String AGENT = "XuanZhi/0.1 (net.xuanzhi.launcher)";
    private static final int MAX_DEP_DEPTH = 6;

    private final OkHttpClient http;
    private final File cacheDir;

    public ModrinthClient(@NonNull Context ctx) {
        http = OkHttpCompat.client();
        this.http = http.newBuilder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder()
                                .header("User-Agent", AGENT)
                                .build()))
                .build();
        this.cacheDir = new File(ctx.getCacheDir(), "modrinth_idx");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
    }

    /* -------------------- 搜索：远端 + 本地索引兜底 -------------------- */

    public List<ModStub> search(@Nullable String query, @Nullable String mc,
                                @Nullable String loader, int limit) throws IOException {
        String facets = buildFacets(mc, loader);
        HttpUrl url = HttpUrl.parse(API + "/search").newBuilder()
                .addQueryParameter("query", query == null ? "" : query)
                .addQueryParameter("limit", String.valueOf(Math.min(limit, 100)))
                .addQueryParameter("index", "relevance")
                .addQueryParameter("facets", facets)
                .build();
        JSONObject root = getJson(url);
        List<ModStub> out = new ArrayList<>();
        JSONArray hits = root.optJSONArray("hits");
        if (hits == null) return out;
        for (int i = 0; i < hits.length(); i++) {
            JSONObject h = hits.getJSONObject(i);
            ModStub s = new ModStub();
            s.source = ID;
            s.projectId = h.optString("project_id", "");
            s.slug = h.optString("slug", "");
            s.title = h.optString("title", "");
            s.summary = h.optString("description", "");
            s.author = h.optString("author", "");
            s.iconUrl = h.optString("icon_url", "");
            s.downloads = h.optLong("downloads", 0);
            s.dateCreated = h.optString("date_created", "");
            s.categories = jsonStrings(h.optJSONArray("display_categories"));
            s.gameVersions = jsonStrings(h.optJSONArray("versions"));
            out.add(s);
        }
        return out;
    }

    /** 本地索引兜底：网络不通时也能搜到之前见过的东西，彻底干掉"搜不到" */
    public List<ModStub> searchLocal(String query, int limit) {
        List<ModStub> all = readLocalIndex();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<>();
        for (ModStub s : all) {
            int sc = score(s, q);
            if (sc > 0) scored.add(new Scored(s, sc));
        }
        Collections.sort(scored, (a, b) -> b.score - a.score);
        List<ModStub> out = new ArrayList<>(limit);
        for (Scored sc : scored) {
            if (out.size() >= limit) break;
            out.add(sc.stub);
        }
        return out;
    }

    /* -------------------- 版本 + 依赖树（核心） -------------------- */

    public List<ModVersion> versions(String projectIdOrSlug, @Nullable String mc,
                                    @Nullable String loader) throws IOException {
        HttpUrl.Builder b = HttpUrl.parse(API + "/project/" + projectIdOrSlug + "/version").newBuilder();
        if (loader != null) b.addQueryParameter("loaders", "[\"" + loader + "\"]");
        if (mc != null) b.addQueryParameter("game_versions", "[\"" + mc + "\"]");
        JSONArray arr = getJsonArray(b.build());
        List<ModVersion> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(parseVersion(arr.getJSONObject(i)));
        return out;
    }

    /** 解析某版本的「必需前置」为一棵平铺树，按依赖顺序排好（被依赖的在前）。
     *  这是 PCL2「装模组连带前置」的等价实现：递归追 project_id，深度上限防环。 */
    public DepGraph resolveDeps(ModVersion root, String mc, String loader) throws IOException {
        DepGraph g = new DepGraph(root);
        Set<String> seen = new HashSet<>();
        seen.add(root.projectId);
        Queue<DepEdge> q = new ArrayDeque<>();
        for (DepEdge e : root.dependencies) if (e.required) q.add(e);
        int depth = 0;
        while (!q.isEmpty() && depth < MAX_DEP_DEPTH) {
            int round = q.size();
            depth++;
            for (int i = 0; i < round; i++) {
                DepEdge e = q.poll();
                if (e.projectId == null || !seen.add(e.projectId)) continue;
                try {
                    ModVersion v = pickCompatible(e.projectId, mc, loader);
                    if (v == null) { g.unresolved.add(e); continue; }
                    g.nodes.add(v);
                    for (DepEdge ne : v.dependencies)
                        if (ne.required && !seen.contains(ne.projectId)) q.add(ne);
                } catch (IOException ignored) { g.unresolved.add(e); }
            }
        }
        g.depth = depth;
        return g;
    }

    @Nullable
    private ModVersion pickCompatible(String projectId, String mc, String loader) throws IOException {
        List<ModVersion> vs = versions(projectId, mc, loader);
        if (!vs.isEmpty()) return vs.get(0);
        List<ModVersion> any = versions(projectId, null, loader);
        return any.isEmpty() ? null : any.get(0);
    }

    /* -------------------- 下载：流式 + SHA1 校验 -------------------- */

    public File download(ModVersion v, File destDir, ProgressSink sink) throws IOException {
        ModFile f = v.primaryFile();
        Request req = new Request.Builder().url(f.url)
                .header("User-Agent", AGENT).build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("Modrinth " + r.code() + " " + f.url);
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
            File out = new File(destDir, f.filename);
            try (InputStream in = r.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                long total = r.body().contentLength(), read = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    read += n;
                    if (sink != null && total > 0) sink.on((int) (read * 100 / total));
                }
            }
            if (f.sha1 != null && !f.sha1.isEmpty()) {
                String got = HashUtil.sha1Hex(out);
                if (!got.equalsIgnoreCase(f.sha1))
                    throw new IOException("校验失败: " + out.getName() + " SHA1 不匹配");
            }
            if (sink != null) sink.on(100);
            return out;
        }
    }

    /* -------------------- 本地索引（搜索永不落空） -------------------- */

    public void mergeIntoLocalIndex(ModStub s) {
        File f = new File(cacheDir, s.projectId + ".json");
        try { new FileOutputStream(f).write(new JSONObject().applySelf(o -> {
            try {
                o.put("id", s.projectId); o.put("slug", s.slug); o.put("title", s.title);
                o.put("summary", s.summary); o.put("author", s.author);
                o.put("gameVersions", new JSONArray(s.gameVersions));
                o.put("categories", new JSONArray(s.categories));
            } catch (Exception ignored) {}
        }).toString(0).getBytes()); } catch (Exception ignored) {}
    }

    private List<ModStub> readLocalIndex() {
        List<ModStub> out = new ArrayList<>();
        File[] fs = cacheDir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        for (File f : fs) {
            try { out.add(stubFromJson(new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()))))); }
            catch (Exception ignored) {}
        }
        return out;
    }

    /* -------------------- JSON 映射 -------------------- */

    private ModVersion parseVersion(JSONObject o) {
        ModVersion v = new ModVersion();
        v.id = o.optString("id", "");
        v.projectId = o.optString("project_id", "");
        v.versionNumber = o.optString("version_number", "");
        v.name = o.optString("name", "");
        v.gameVersions = jsonStrings(o.optJSONArray("game_versions"));
        v.loaders = jsonStrings(o.optJSONArray("loaders"));
        v.datePublished = o.optString("date_published", "");
        JSONArray files = o.optJSONArray("files");
        if (files != null)
            for (int i = 0; i < files.length(); i++) v.files.add(parseFile(files.getJSONObject(i)));
        JSONArray deps = o.optJSONArray("dependencies");
        if (deps != null)
            for (int i = 0; i < deps.length(); i++) v.dependencies.add(parseDep(deps.getJSONObject(i)));
        return v;
    }

    private ModFile parseFile(JSONObject o) {
        ModFile f = new ModFile();
        f.url = o.optString("url", "");
        f.filename = o.optString("filename", "");
        f.size = o.optLong("size", -1);
        f.primary = o.optBoolean("primary", false);
        JSONObject h = o.optJSONObject("hashes");
        if (h != null) f.sha1 = h.optString("sha1", "");
        return f;
    }

    private DepEdge parseDep(JSONObject o) {
        DepEdge e = new DepEdge();
        e.versionId = o.optString("version_id", "");
        e.projectId = o.optString("project_id", "");
        e.fileName = o.optString("file_name", "");
        String t = o.optString("dependency_type", "required");
        e.required = "required".equals(t) || "requiredDependency".equals(t);
        e.type = t;
        return e;
    }

    private ModStub stubFromJson(JSONObject o) {
        ModStub s = new ModStub();
        s.source = ID; s.projectId = o.optString("id", ""); s.slug = o.optString("slug", "");
        s.title = o.optString("title", ""); s.summary = o.optString("summary", "");
        s.author = o.optString("author", "");
        s.gameVersions = jsonStrings(o.optJSONArray("gameVersions"));
        s.categories = jsonStrings(o.optJSONArray("categories"));
        return s;
    }

    /* -------------------- 工具 -------------------- */

    private JSONObject getJson(HttpUrl url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("Modrinth " + r.code() + " " + url);
            return new JSONObject(r.body.string());
        }
    }

    private JSONArray getJsonArray(HttpUrl url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("Modrinth " + r.code() + " " + url);
            return new JSONArray(r.body.string());
        }
    }

    private String buildFacets(@Nullable String mc, @Nullable String loader) {
        List<String> parts = new ArrayList<>();
        parts.add("[\"project_type:mod\"]");
        if (loader != null) parts.add("[\"categories:" + loader + "\"]");
        if (mc != null) parts.add("[\"versions:" + mc + "\"]");
        return "[" + String.join(",", parts) + "]";
    }

    private static List<String> jsonStrings(@Nullable JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) { String t = a.optString(i, null); if (t != null) out.add(t); }
        return out;
    }

    private static int score(ModStub s, String q) {
        if (q.isEmpty()) return 1;
        String hay = (s.title + " " + s.summary + " " + s.author + " " + s.slug).toLowerCase(Locale.ROOT);
        if (hay.contains(q)) return 100;
        String[] toks = q.split("\\s+");
        int hit = 0;
        for (String t : toks) if (hay.contains(t)) hit += 40;
        return hit;
    }

    private static final class Scored { final ModStub stub; final int score;
        Scored(ModStub s, int sc) { stub = s; score = sc; } }

    @FunctionalInterface
    public interface ProgressSink { void on(int percent); }
}
