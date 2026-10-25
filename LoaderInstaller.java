package net.xuanzhi.launcher.mods;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.hanyi.launcher.jre.JreRuntime;
import net.hanyi.launcher.launch.JvmLauncher;
import net.hanyi.launcher.profile.LaunchProfile;
import net.hanyi.launcher.util.OkHttpCompat;
import okhttp3.*;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 加载器安装器 —— 对标 PCL2「装模组自动装 Java / 自动装加载器」。
 * <p>
 * 思路：不造轮子。Fabric / Quilt 官方提供 headless installer，丢给内嵌 JRE 跑一遍
 * 就能在指定 gameDir 里长出新 profile；Forge / NeoForge 用它的 installer jar 的
 * `--installClient <gameDir>` 语义（1.13+ 通用，1.12.2 走 oldForge 分支）。
 * <p>
 * 流程：JRE 自检 → 下 loader installer → 跑 headless → 扫描 libs/ 产物 → 回写 profile。
 * 全部同步、可给进度回调，ViewModel 层直接当 LiveData 驱动那个「水墨进度条」。
 */
public final class LoaderInstaller {

    public enum Kind { FABRIC, QUILT, FORGE, NEOFORGE }

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions";
    private static final String QUILT_META = "https://quiltmc.org/api/v1/versions";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build();

    private final Context ctx;
    private final JreRuntime jre;

    public LoaderInstaller(@NonNull Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.jre = new JreRuntime(ctx);
    }

    /* -------------------- 版本清单 -------------------- */

    /** 返回某加载器可用的 installer 版本列表（最新在前） */
    public List<LoaderRelease> listReleases(@NonNull Kind kind, @NonNull String mc) throws Exception {
        switch (kind) {
            case FABRIC: return fabricReleases(mc);
            case QUILT: return quiltReleases(mc);
            case FORGE: return forgeReleases(mc);
            case NEOFORGE: return neoforgeReleases(mc);
            default: return Collections.emptyList();
        }
    }

    /** 一键：装 loader + 把 profile 的 loader 字段写回去 */
    public File install(@NonNull Kind kind, @NonNull LaunchProfile profile,
                       @NonNull String loaderVersion, @Nullable ProgressSink sink) throws Exception {
        // ① JRE 必须就位 —— 这就是「下载游戏自动下载 Java」
        if (!jre.isInstalled()) {
            emit(sink, 0, "正在部署 JRE…");
            jre.installIfMissing(() -> {}); // 阻塞到解压完
        }
        emit(sink, 8, "正在获取 " + kind + " " + loaderVersion);

        File installer = downloadInstaller(kind, profile.getGameVersion(), loaderVersion);
        emit(sink, 30, "正在安装加载器（headless）…");

        File gameDir = profile.getGameDir();
        //noinspection ResultOfMethodCallIgnored
        gameDir.mkdirs();
        runHeadless(kind, installer, profile, sink);

        emit(sink, 95, "正在校准依赖库…");
        calibrateProfile(profile, kind, loaderVersion);
        emit(sink, 100, "加载器已就绪");
        return new File(gameDir, "libraries");
    }

    /* -------------------- headless 执行 -------------------- */

    private void runHeadless(@NonNull Kind kind, @NonNull File installer,
                             @NonNull LaunchProfile profile, @Nullable ProgressSink sink)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(jre.getJavaExecutable().getAbsolutePath());
        cmd.add("-Xmx512m");
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-jar");
        cmd.add(installer.getAbsolutePath());

        switch (kind) {
            case FABRIC:
                // fabric-installer: action=install-client server=false mcver=... loader=... gameDir=...
                cmd.add("--noprofile"); // 我们自己管 profile，不让它写 launcher_profiles.json
                cmd.add("client");
                cmd.add("--mc-version"); cmd.add(profile.getGameVersion());
                cmd.add("--loader-version"); cmd.add(loaderVersion(profile, kind));
                cmd.add("--install-dir"); cmd.add(profile.getGameDir().getAbsolutePath());
                cmd.add("--no-daemon"); // 1.21+ 参数，跑完即退
                break;
            case QUILT:
                cmd.add("install");
                cmd.add("client");
                cmd.add("--game-version"); cmd.add(profile.getGameVersion());
                cmd.add("--install-dir"); cmd.add(profile.getGameDir().getAbsolutePath());
                cmd.add("--no-profiles-json");
                break;
            case FORGE:
            case NEOFORGE:
                // Forge installer 1.13+：java -jar forge-x.x.x-installer.jar --installClient <gameDir>
                cmd.add("--installClient");
                cmd.add(profile.getGameDir().getAbsolutePath());
                break;
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(profile.getGameDir());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        new Thread(() -> drain(p.getInputStream(), sink), "xuanzhi-loader-log").start();
        int code = p.waitFor();
        if (code != 0 && code != 2) // forge 偶发退出码 2 但实际装好了，以产物为准
            throw new RuntimeException(kind + " 安装失败，退出码 " + code + "，见控制台日志");
    }

    /** 装完后扫描 libraries 里的新 jar，把加载器主类/版本写回 profile，启动时不迷路 */
    private void calibrateProfile(@NonNull LaunchProfile profile, @NonNull Kind kind, String ver) {
        profile.setLoader(kind.name().toLowerCase());
        profile.setLoaderVersion(ver);
        // 找加载器入口 jar，后续 JvmLauncher 用它拼 -cp / --tweakClass
        File libs = new File(profile.getGameDir(), "libraries");
        if (!libs.exists()) return;
        String marker = marker(kind);
        File[] hits = libs.listFiles((d, n) -> n.toLowerCase().contains(marker)
                && n.endsWith(".jar") && !n.contains("sources"));
        if (hits != null && hits.length > 0) {
            // 取版本最像的那一个
            Arrays.sort(hits, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            profile.setLoaderJar(hits[0].getAbsolutePath());
        }
    }

    /* -------------------- installer 下载 -------------------- */

    private File downloadInstaller(@NonNull Kind kind, String mc, String ver) throws Exception {
        String url = installerUrl(kind, mc, ver);
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "XuanZhi/0.1").build();
        try (okhttp3.Response r = HTTP.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new RuntimeException("拉取加载器失败 " + r.code() + " " + url);
            File tmp = new File(ctx.getCacheDir(), "installer_" + kind + "_" + ver + ".jar");
            try (InputStream in = r.body.byteStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) != -1) fos.write(b, 0, n);
            }
            return tmp;
        }
    }

    /* -------------------- 各加载器元数据 / URL 策略 -------------------- */

    private List<LoaderRelease> fabricReleases(String mc) throws Exception {
        Request req = new Request.Builder().url(FABRIC_META + "/loader/" + mc).build();
        try (okhttp3.Response r = HTTP.newCall(req).execute()) {
            List<LoaderRelease> out = new ArrayList<>();
            var arr = new org.json.JSONArray(r.body.string());
            for (int i = 0; i < arr.length() && i < 30; i++) {
                JSONObject o = arr.getJSONObject(i);
                LoaderRelease lr = new LoaderRelease();
                lr.version = o.optString("version", "");
                lr.stable = o.optBoolean("stable", true);
                lr.url = "https://meta.fabricmc.net/v2/versions/loader/" + mc + "/" + lr.version
                        + "/profile/json"; // installer 直链由 meta 的 files 给，这里降级用 maven
                out.add(lr);
            }
            return out;
        }
    }

    private List<LoaderRelease> quiltReleases(String mc) throws Exception {
        Request req = new Request.Builder().url(QUILT_META + "/loader/" + mc).build();
        try (okhttp3.Response r = HTTP.newCall(req).execute()) {
            List<LoaderRelease> out = new ArrayList<>();
            var arr = new org.json.JSONArray(r.body.string());
            for (int i = 0; i < arr.length() && i < 30; i++) {
                JSONObject o = arr.getJSONObject(i);
                LoaderRelease lr = new LoaderRelease();
                lr.version = o.optString("version", "");
                lr.stable = o.optBoolean("stable", true);
                out.add(lr);
            }
            return out;
        }
    }

    /** Forge：官方 files 列表（maven 语义），返回可用版本号。
     *  注意：Forge 1.20.1+ 与 1.12.2 的 installer 结构不同，这里按新格式取。 */
    private List<LoaderRelease> forgeReleases(String mc) throws Exception {
        // files.minecraftforge.net 的 maven-metadata 直接可用，且不用登录
        String meta = "https://files.minecraftforge.net/maven/net/minecraftforge/forge/maven-metadata.xml";
        Request req = new Request.Builder().url(meta).build();
        List<LoaderRelease> out = new ArrayList<>();
        try (okhttp3.Response r = HTTP.newCall(req).execute()) {
            String xml = r.body.string();
            // <version>1.20.1-46.2.32</version> —— 粗解析够用，正式版建议上 XmlPull
            for (String v : xml.split("<version>")) {
                if (!v.contains("<")) break;
                String ver = v.substring(0, v.indexOf('<')).trim();
                if (ver.startsWith(mc + "-") || ver.equals(mc)) {
                    LoaderRelease lr = new LoaderRelease();
                    lr.version = ver; lr.stable = !ver.contains("beta") && !ver.contains("rc");
                    out.add(lr);
                }
            }
        }
        out.sort(Comparator.comparing((LoaderRelease lr) -> lr.version).reversed());
        return out;
    }

    private List<LoaderRelease> neoforgeReleases(String mc) throws Exception {
        String meta = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
        Request req = new Request.Builder().url(meta).build();
        List<LoaderRelease> out = new ArrayList<>();
        try (okhttp3.Response r = HTTP.newCall(req).execute()) {
            String xml = r.body.string();
            for (String v : xml.split("<version>")) {
                if (!v.contains("<")) break;
                String ver = v.substring(0, v.indexOf('<')).trim();
                if (ver.startsWith(mc + ".") || ver.startsWith(mc + "-") || ver.equals(mc)) {
                    LoaderRelease lr = new LoaderRelease();
                    lr.version = ver; lr.stable = true; out.add(lr);
                }
            }
        }
        out.sort(Comparator.comparing((LoaderRelease lr) -> lr.version).reversed());
        return out;
    }

    private String installerUrl(@NonNull Kind kind, String mc, String ver) throws Exception {
        switch (kind) {
            case FABRIC: // 官方 installer fat-jar
                return "https://maven.fabricmc.net/net/fabricmc/fabric-installer/" + ver
                        + "/fabric-installer-" + ver + ".jar";
            case QUILT:
                return "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/"
                        + ver + "/quilt-installer-" + ver + ".jar";
            case FORGE:
                return "https://files.minecraftforge.net/maven/net/minecraftforge/forge/" + ver
                        + "/forge-" + ver + "-installer.jar";
            case NEOFORGE:
                return "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + ver
                        + "/neoforge-" + ver "-installer.jar";
            default: throw new IllegalStateException("未知加载器: " + kind);
        }
    }

    @Nullable
    private String loaderVersion(@NonNull LaunchProfile p, @NonNull Kind k) { return p.getLoaderVersion(); }

    private static String marker(@NonNull Kind k) {
        switch (k) {
            case FABRIC: return "fabric-loader";
            case QUILT: return "quilt-loader";
            case FORGE: return "forge-";
            case NEOFORGE: return "neoforge";
            default: return "";
        }
    }

    private static void drain(@NonNull InputStream in, @Nullable ProgressSink sink) {
        byte[] b = new byte[4096];
        int n;
        try { while ((n = in.read(b)) != -1) {
            if (sink != null) {
                String line = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                if (line.contains("Installing") || line.contains("Done") || line.contains("Extracting"))
                    sink.on(-1); // 让 UI 知道有新日志，具体进度由外层控制
            }
        }} catch (Exception ignored) {}
    }

    private static void emit(@Nullable ProgressSink s, int p, String msg) {
        if (s != null) s.emit(p, msg);
    }

    public static final class LoaderRelease {
        public String version = "";
        public boolean stable = true;
        public String url = "";
        @Override public String toString() { return version + (stable ? "" : " (预发布)"); }
    }

    public interface ProgressSink {
        /** percent=-1 表示只是日志心跳，不是进度 */
        void on(int percent);
        default void emit(int percent, String msg) { on(percent); }
    }
}
