package net.xuanzhi.launcher.profile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个「启动配置」= 一个版本 + 一套 JVM 参数 + 一个账号（可选）。
 * 对应启动器里的一个卡片。多开/版本隔离就靠这个：每个 profile 一个独立游戏目录。
 */
public final class LaunchProfile {

    @NonNull public String id = "default";
    @NonNull public String versionId = "";          // manifest 里的版本 id
    @NonNull public String gameDir = "";            // 该 profile 的独立目录
    @NonNull public String accountLocalId = "";    // 绑定的账号，空=离线

    /** 最大堆，MB。0 = 按设备内存自动算 */
    public int maxMemoryMb = 0;
    /** 额外 JVM 参数，空格分隔；留空就用下面 defaults */
    @NonNull public String extraJvmArgs = "";
    /** 渲染后端，对应 JRE 启动参数里的 renderer 开关 */
    @NonNull public Renderer renderer = Renderer.HOLY_GL4ES;
    /** 是否开分代 ZGC / G1 等，手机上默认 G1 */
    public boolean useZgc = false;
    /** 额外 game arguments（高级用户） */
    @NonNull public String extraGameArgs = "";
    @NonNull public String loader = "";
    @Nullable public String loaderVersion = "";
    @Nullable public String loaderJar = "";

    @NonNull
    public String getLoader() { return loader == null ? "" : loader; }
    public void setLoader(@Nullable String l) { loader = l == null ? "" : l; }
    @Nullable
    public String getLoaderVersion() { return loaderVersion; }
    public void setLoaderVersion(@Nullable String v) { loaderVersion = v == null ? "" : v; }
    @Nullable
    public String getLoaderJar() { return loaderJar; }
    public void setLoaderJar(@Nullable String j) { loaderJar = j == null ? "" : j; }

    /** 模组目录：加载器装完后必然是 gameDir/mods */
    @NonNull
    public java.io.File getModsDir() {
        java.io.File d = new java.io.File(getGameDir(), "mods");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 版本槽名：目录隔离 + 日志标识 */
    @NonNull
    public String getVersionSlot() {
        if (loader.isEmpty()) return getGameVersion();
        String lv = loaderVersion == null || loaderVersion.isEmpty() ? getGameVersion() : loaderVersion;
        return loader + "-" + lv;
    }
    public enum Renderer {
        /** 系统已装的 MobileGlues 插件，自动探测绑定（见 RendererRegistry） */
        MOBILEGLUES("MobileGlues"),
        /** 老版本 1.12.2 以下稳 */
        GL4ES("GL4ES"),
        /** 1.17+ 默认，靠 Holy GL4ES 翻译 */
        HOLY_GL4ES("Holy GL4ES"),
        /** 走 ANGLE/Vulkan，部分新机型更快 */
        ANGLE("ANGLE"),
        /** 纯软渲染，没驱动就用它，慢但能跑 */
        OSMESA("OSMesa");

        public final String label;
        Renderer(String l) { label = l; }

        /** 转成 JVM 参数片段，交给 JvmLauncher 拼进命令行 */
        @NonNull public String[] toJvmFlags() {
            switch (this) {
                case GL4ES:     return new String[]{ "-Dgl4es.verbose=0", "-Dorg.lwjgl.opengl.libname=libgl4es_android.so" };
                case HOLY_GL4ES:return new String[]{ "-Dorg.lwjgl.opengl.libname=libholygl4es.so" };
                case ANGLE:     return new String[]{ "-Dorg.lwjgl.opengl.libname=libangle.so", "-Dangle.egl=1" };
                case MOBILEGLUES:
                    // 真正的 so / 环境变量由 RendererRegistry.envFor 注入，这里只给 lwjgl 一个保底名
                    return new String[]{ "-Dorg.lwjgl.opengl.libname=libmobileglues.so",
                            "-Dpojav.renderer=opengles3", "-Dlibgl.egl=libmobileglues.so" };
                case OSMESA:
                    return new String[]{ "-Dorg.lwjgl.opengl.libname=libOSMesa.so", "-Dosmesa.software=1" };
                default:        return new String[0];
            }
        }
    }

    /** 算默认 -Xmx：留 1GB 给系统 + 启动器自己，剩下的一半给游戏，下限 512MB 上限 4096MB。 */
    public int resolvedMaxMemoryMb() {
        if (maxMemoryMb > 0) return Math.min(maxMemoryMb, 4096);
        long total = Runtime.getRuntime().maxMemory(); // 不太准，真实环境建议读 /proc/meminfo
        int guess = (int) Math.max(512, Math.min(4096, (total / 2 / 1024 / 1024) - 512));
        return guess;
    }

    /** 默认 JVM 参数骨架。用户 extraJvmArgs 追加在后面，可以覆盖前面的。 */
    @NonNull
    public String[] baseJvmArgs() {
        int mx = resolvedMaxMemoryMb();
        return new String[]{
                "-Xms" + Math.max(256, mx / 2) + "M",
                "-Xmx" + mx + "M",
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=50",
                "-XX:+DisableExplicitGC",
                "-Djava.awt.headless=false",
                "-Djna.nosys=true",
                "-Dorg.lwjgl.system.allocator=jemalloc",
                "-Duser.language=zh"
        };
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("versionId", versionId); o.put("gameDir", gameDir);
            o.put("accountLocalId", accountLocalId);
            o.put("maxMemoryMb", maxMemoryMb); o.put("extraJvmArgs", extraJvmArgs);
            o.put("renderer", renderer.name()); o.put("useZgc", useZgc);
            try { o.put("extraEnv", new org.json.JSONObject()); } catch (Exception ignored) {}
            o.put("extraGameArgs", extraGameArgs);
        } catch (JSONException ignore) {}
        return o;
    }

    @NonNull
    public static LaunchProfile fromJson(@NonNull JSONObject o) {
        LaunchProfile p = new LaunchProfile();
        p.id       = o.optString("id", p.id);
        p.versionId = o.optString("versionId", "");
        p.gameDir  = o.optString("gameDir", "");
        p.accountLocalId = o.optString("accountLocalId", "");
        p.maxMemoryMb = o.optInt("maxMemoryMb", 0);
        p.extraJvmArgs = o.optString("extraJvmArgs", "");
        try { p.renderer = Renderer.valueOf(o.optString("renderer", "HOLY_GL4ES")); }
        catch (IllegalArgumentException e) { p.renderer = Renderer.HOLY_GL4ES; }
        p.useZgc = o.optBoolean("useZgc", false);
        try { p.extraEnv.clear(); org.json.JSONObject eo = o.optJSONObject("extraEnv"); if (eo != null) { for (String k : eo.keySet()) p.extraEnv.put(k, eo.getString(k)); } } catch (Exception ignored) {}
        p.extraGameArgs = o.optString("extraGameArgs", "");
        return p;
    }

    /** 游戏目录未配置时的合理默认值：<base>/instances/<versionId> */
    @NonNull
    public static String defaultGameDir(@NonNull String base, @NonNull String versionId) {
        return base + "/instances/" + versionId.replace('/', '_');
    }

    /** 从 SharedPreferences（profile 作用域）读回，和 SettingsActivity 的 key 一一对应。 */
    @NonNull
    public static LaunchProfile fromPrefs(@NonNull android.content.SharedPreferences sp, @NonNull String versionId) {
        LaunchProfile p = new LaunchProfile();
        p.versionId = versionId;
        String m = sp.getString("max_mem", "");
        try { if (!m.isEmpty()) p.maxMemoryMb = Integer.parseInt(m); } catch (NumberFormatException ignore) {}
        p.extraJvmArgs = sp.getString("jvm_args", "").trim();
        try { p.renderer = Renderer.valueOf(sp.getString("renderer", "HOLY_GL4ES")); }
        catch (IllegalArgumentException e) { p.renderer = Renderer.HOLY_GL4ES; }
        p.extraGameArgs = sp.getString("game_args", "").trim();
        String gd = sp.getString("game_dir", "").trim();
        p.gameDir = gd.isEmpty() ? defaultGameDir(
                net.xuanzhi.launcher.LauncherApp.get().baseDir().getAbsolutePath(), versionId) : gd;
        return p;
    }
}

}
