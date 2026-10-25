package net.xuanzhi.launcher.meta;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hanyi.launcher.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 版本元数据仓库：远端 manifest -> 内存 -> 本地缓存。
 * 缓存策略：manifest 内存常驻；单个 version.json 落盘到 <base>/versions/_cache/，
 * 超过 1 小时就重新拉（版本发布很频繁，别缓存太久）。
 */
public final class MetaRepository {

    private final OkHttpClient http;
    private final ObjectMapper om;
    private final File cacheDir;

    private VersionManifest cached;

    public MetaRepository(@NonNull OkHttpClient http, @NonNull ObjectMapper om, @NonNull File cacheDir) {
        this.http = http;
        this.om = om;
        this.cacheDir = new File(cacheDir, "versions/_cache");
        this.cacheDir.mkdirs();
    }

    @NonNull
    public VersionManifest manifest() throws IOException {
        if (cached != null) return cached;
        Request req = new Request.Builder().url(BuildConfig.META_URL).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null)
                throw new IOException("拉取版本清单失败: " + resp.code());
            cached = om.readValue(resp.body().string(), VersionManifest.class);
            return cached;
        }
    }

    /** 拿某个版本的 version.json。本地有新鲜缓存就用本地的。 */
    @NonNull
    public VersionMeta version(@NonNull VersionManifest.Entry e) throws IOException {
        File f = new File(cacheDir, e.id + ".json");
        if (f.exists() && f.lastModified() > System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)) {
            try { return om.readValue(f, VersionMeta.class); } catch (IOException ignore) {}
        }
        Request req = new Request.Builder().url(e.url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null)
                throw new IOException("拉取 " + e.id + " 元数据失败: " + resp.code());
            String s = resp.body().string();
            om.writeValue(f, om.readTree(s)); // 落盘
            return om.readValue(s, VersionMeta.class);
        }
    }

    /** 按 id 在 manifest 里找条目，找不到返回 null。 */
    @Nullable
    public VersionManifest.Entry find(@NonNull String id) throws IOException {
        for (VersionManifest.Entry e : manifest().versions)
            if (e.id.equals(id)) return e;
        return null;
    }

    /** 最新 release。 */
    @Nullable
    public VersionManifest.Entry latestRelease() throws IOException {
        String r = manifest().latest == null ? null : manifest().latest.release;
        return r == null ? null : find(r);
    }
}
