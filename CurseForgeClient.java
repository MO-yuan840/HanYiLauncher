package net.xuanzhi.launcher.mods;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.hanyi.launcher.BuildConfig;
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
 * CurseForge 源。官方 Core API 必须 X-Api-Key；未配置时整个源自动跳过，
 * 绝不让它把别的源一起拖垮（这是 PCL2 搜不到模组的常见原因之一：一个源超时全崩）。
 * <p>
 * 字段口径（官方 v1）：
 *  search  → GET /v1/mods/search?gameId=432&classId=6&searchFilter=&gameVersion=&modLoaderType=&sortField=
 *  files   → GET /v1/mods/{id}/files?gameVersion=&modLoaderType=
 *  file    → GET /v1/mods/{id}/files/{fileId}
 *  deps    → file.dependencies[] { modId, relationType: 3=required,2=optional,5=incompatible,1=embedded,4=tool,6=include }
 *  download→ file.downloadUrl（直接 CDN），2026 起强制带 key，否则 401
 */
public final class CurseForgeClient {

    public static final String ID = "curseforge";
    private static final String API = "https://api.curseforge.com/v1";
    private static final int GAME_MC = 432, CLASS_MOD = 6;
    private static final String AGENT = "XuanZhi/0.1 (net.xuanzhi.launcher)";

    // 加载器 → 官方 modLoaderType
    public static int loaderType(@Nullable String loader) {
        if (loader == null) return 0;
        switch (loader.toLowerCase(Locale.ROOT)) {
            case "forge": return 1;
            case "cauldron": return 2;
            case "liteloader": return 3;
            case "fabric": return 4;
            case "quilt": return 5;
            case "neoforge": return 6;
            default: return 0;
        }
    }

    private final OkHttpClient http;
    private final String key;

    public CurseForgeClient(@NonNull Context ctx) {
        this.key = apiKey();
        this.http = OkHttpCompat.client().newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request.Builder b = chain.request().newBuilder()
                            .header("User-Agent", AGENT)
                            .header("Accept", "application/json");
                    if (key != null && !key.isEmpty()) b.header("X-Api-Key", key);
                    return chain.proceed(b.build());
                })
                .build();
    }

    /** 没配 Key 就直接说「此源不可用」，上层并发时跳过而不是抛异常。 */
    public boolean available() { return key != null && !key.isEmpty(); }

    public List<ModStub> search(@Nullable String query, @Nullable String mc,
                                @Nullable String loader, int limit) throws IOException {
        if (!available()) return Collections.emptyList();
        HttpUrl.Builder b = HttpUrl.parse(API + "/mods/search").newBuilder()
                .addQueryParameter("gameId", String.valueOf(GAME_MC))
                .addQueryParameter("classId", String.valueOf(CLASS_MOD))
                .addQueryParameter("pageSize", String.valueOf(Math.min(limit, 50)))
                .addQueryParameter("sortField", "2"); // 2 = Last Updated
        if (query != null && !query.isEmpty()) b.addQueryParameter("searchFilter", query);
        if (mc != null) b.addQueryParameter("gameVersion", mc);
        int lt = loaderType(loader);
        if (lt != 0) b.addQueryParameter("modLoaderType", String.valueOf(lt));
        JSONObject root = getJson(b.build());
        List<ModStub> out = new ArrayList<>();
        JSONArray data = root.optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) out.add(stub(data.getJSONObject(i)));
        return out;
    }

    public ModVersion pickVersion(long modId, @Nullable String mc, @Nullable String loader) throws IOException {
        if (!available()) return null;
        HttpUrl.Builder b = HttpUrl.parse(API + "/mods/" + modId + "/files").newBuilder();
        if (mc != null) b.addQueryParameter("gameVersion", mc);
        int lt = loaderType(loader);
        if (lt != 0) b.addQueryParameter("modLoaderType", String.valueOf(lt));
        JSONObject root = getJson(b.build());
        JSONArray files = root.optJSONArray("data");
        if (files == null || files.length() == 0) return null;
        // CF 默认按发布倒序，取第一个即可
        return version(files.getJSONObject(0), String.valueOf(modId));
    }

    /** 拿直链。CF 的 CDN 要带 key，所以我们始终用官方 file 接口取 downloadUrl */
    @Nullable
    public String resolveUrl(long modId, long fileId) throws IOException {
        if (!available()) return null;
        JSONObject o = getJson(HttpUrl.parse(API + "/mods/" + modId + "/files/" + fileId).build());
        JSONObject f = o.optJSONObject("data");
        return f == null ? null : f.optString("downloadUrl", null);
    }

    public File download(ModVersion v, File destDir, ModrinthClient.ProgressSink sink) throws IOException {
        String url = v.cfDownloadUrl;
        if (url == null) url = resolveUrl(v.cfModId, v.cfFileId);
        if (url == null) throw new IOException("CurseForge 未取到下载直链（可能需要 API Key）");
        Request req = new Request.Builder().url(url)
                .header("User-Agent", AGENT)
                .header("X-Api-Key", key).build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) {
                if (r.code() == 401) throw new IOException("CurseForge 拒绝下载：API Key 无效或未授权");
                throw new IOException("CurseForge " + r.code());
            }
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
            File out = new File(destDir, v.primaryFile().filename);
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
            if (sink != null) sink.on(100);
            return out;
        }
    }

    /* -------------------- 映射 -------------------- */

    private ModStub stub(JSONObject o) {
        ModStub s = new ModStub();
        s.source = ID;
        s.projectId = String.valueOf(o.optInt("id", 0));
        s.slug = o.optString("slug", "");
        s.title = o.optString("name", "");
        s.summary = o.optString("summary", "");
        s.author = o.optString("authors", "");
        s.downloads = o.optLong("downloadCount", 0);
        s.cfModId = o.optLong("id", 0);
        JSONObject logo = o.optJSONObject("logo");
        if (logo != null) s.iconUrl = logo.optString("thumbnailUrl", "");
        JSONArray cats = o.optJSONArray("categories");
        if (cats != null) for (int i = 0; i < cats.length(); i++)
            s.categories.add(cats.optString(i, ""));
        return s;
    }

    private ModVersion version(JSONObject f, String modId) {
        ModVersion v = new ModVersion();
        v.projectId = modId;
        v.cfModId = f.optLong("modId", 0);
        v.cfFileId = f.optLong("id", 0);
        v.versionNumber = f.optString("displayName", "");
        v.name = f.optString("fileName", "");
        JSONArray gv = f.optJSONArray("gameVersions");
        if (gv != null) for (int i = 0; i < gv.length(); i++) {
            String s = gv.optString(i, "");
            if (!s.isEmpty()) v.gameVersions.add(s);
        }
        ModFile mf = new ModFile();
        mf.filename = f.optString("fileName", "");
        mf.size = f.optLong("fileLength", -1);
        mf.primary = true;
        v.files.add(mf);
        JSONArray deps = f.optJSONArray("dependencies");
        if (deps != null) for (int i = 0; i < deps.length(); i++) {
            JSONObject d = deps.getJSONObject(i);
            DepEdge e = new DepEdge();
            e.projectId = String.valueOf(d.optInt("modId", 0));
            e.cfModId = d.optLong("modId", 0);
            e.cfFileId = d.optLong("fileId", 0);
            int rel = d.optInt("relationType", 0);
            // 3 = requiredDependency；include(6) 也一并装，embedded(1) 不重复下
            e.required = (rel == 3 || rel == 6);
            e.type = relType(rel);
            v.dependencies.add(e);
        }
        return v;
    }

    private static String relType(int r) {
        switch (r) {
            case 1: return "embeddedLibrary";
            case 2: return "optional";
            case 3: return "required";
            case 4: return "tool";
            case 5: return "incompatible";
            case 6: return "include";
            default: return "unknown";
        }
    }

    private JSONObject getJson(HttpUrl url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) {
                if (r.code() == 401) throw new IOException("CurseForge 401：请在设置里填写有效的 API Key");
                throw new IOException("CurseForge " + r.code() + " " + url);
            }
            return new JSONObject(r.body.string());
        }
    }

    @Nullable
    private static String apiKey() {
        String k = BuildConfig.CF_API_KEY;
        return (k == null || k.trim().isEmpty() || k.startsWith("PLACEHOLDER")) ? null : k.trim();
    }
}
