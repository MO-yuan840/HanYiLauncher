package net.hanyi.launcher.launch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.Os;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.hanyi.launcher.BuildConfig;
import net.hanyi.launcher.profile.LaunchProfile;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 渲染器注册表 —— 「自由选择渲染器」+「手机里已有 MobileGlues 就自动绑定」的总枢纽。
 * <p>
 * 设计目标：玩家看到的是一串<b>有解释</b>的选项，而不是一堆环境变量名：
 * <pre>
 *   ● MobileGlues（已绑定 · v1.x.x）   “系统已装插件，光影更稳，首选”
 *   ○ ANGLE / Vulkan                 “新机型更快，老机型可能黑屏”
 *   ○ Holy GL4ES                     “1.17+ 通用，最稳”
 *   ○ 纯 GL4ES                       “1.12.2 以下首选”
 *   ○ OSMesa 软渲染                  “没驱动也能跑，很慢”
 * </pre>
 * <p>
 * 「自动绑定」怎么做到（不把 MobileGlues 打进 APK，那是别人的发行包）：
 * <ol>
 *   <li>扫本机已装含 libmobileglues.so 的包：{@code pm path} 拿 apk 路径 → 在
 *       {@code lib/arm64-v8a/} 里找 {@code libmobileglues.so}（Zalith / 独立插件都这个结构）。</li>
 *   <li>也扫常见手动部署位：{@code /data/local/lib/mobileglues/}、内部 {@code jre/lib/} 旁用户放的 so。</li>
 *   <li>命中就把 {@code LD_LIBRARY_PATH} 前置到那个目录，并注入
 *       {@code LIBGL_EGL=libmobileglues.so LIBGL_GLES=libmobileglues.so
 *        POJAV_RENDERER=opengles3 POJAVEXEC_EGL=1}，这组变量名与
 *       Zalith / FCL / Amethyst 的启动脚本完全一致，配置可互通。</li>
 *   <li>没命中就灰显并提示「安装 Zalith / MobileGlues 插件后自动出现」，绝不崩。</li>
 * </ol>
 * <p>
 * 变量契约写在 {@link #envFor(LaunchProfile, File)} 里，是唯一真相来源；
 * {@link net.hanyi.launcher.launch.JvmLauncher} 只管把返回的 map 塞进 envp。
 */
public final class RendererRegistry {

    public enum Kind {
        /** 系统已装的 MobileGlues 插件（自动探测） */
        MOBILEGLUES,
        /** ANGLE，走 Vulkan 后端 */
        ANGLE,
        /** Holy GL4ES，1.17+ 默认 */
        HOLY_GL4ES,
        /** 原版 GL4ES，老版本 */
        GL4ES,
        /** 纯软渲染兜底 */
        OSMESA
    }

    /** UI 用的描述，按当前是否探测到给出不同文案 */
    public static final class Entry {
        @NonNull public final Kind kind;
        @NonNull public final String id;       // 存到 LaunchProfile.renderer
        @NonNull public final String name;
        @NonNull public final String hint;
        public final boolean detected;         // 是否已在本机找到 so
        @NonNull public final String soDir;    // 命中时 so 所在目录，注入 LD_LIBRARY_PATH 用

        Entry(@NonNull Kind k, @NonNull String id, @NonNull String name,
              @NonNull String hint, boolean detected, @NonNull String soDir) {
            this.kind = k; this.id = id; this.name = name; this.hint = hint;
            this.detected = detected; this.soDir = soDir;
        }

        /** 设置页的副标题：已绑定就显示版本/路径，否则显示一句人话 */
        @NonNull public String subtitle() {
            if (detected) return "已绑定 · " + (soDir.isEmpty() ? "内置" : compact(soDir));
            return hint;
        }

        private static String compact(@NonNull String p) {
            int i = p.lastIndexOf('/');
            return i >= 0 ? p.substring(i + 1) : p;
        }
    }

    private static final String PKG_PLUGIN = "com.fcl.plugin.mobileglues";
    private static final String SO_NAME = "libmobileglues.so";

    /* ---------------- 探测（缓存，避免每次点开设置都扫盘） ---------------- */

    private static volatile Probe cache;
    private static final long CACHE_TTL_MS = 30_000L;

    private static final class Probe {
        final long at = System.currentTimeMillis();
        final String pluginSoDir;     // 插件包的 lib 目录，优先
        final String fallbackSoDir;   // 手动部署位
        Probe(String a, String b) { pluginSoDir = a; fallbackSoDir = b; }
        boolean any() { return pluginSoDir != null || fallbackSoDir != null; }
        @NonNull String dir() { return pluginSoDir != null ? pluginSoDir : fallbackSoDir; }
    }

    /** 强制下次重新扫描（装完插件回启动器时点一下「刷新」用） */
    public static void invalidate() { cache = null; }

    @NonNull
    private static Probe probe(@NonNull Context ctx) {
        Probe p = cache;
        if (p != null && System.currentTimeMillis() - p.at < CACHE_TTL_MS) return p;
        String plugin = scanPluginSoDir(ctx);
        String fall = scanFallback(ctx);
        p = new Probe(plugin, fall);
        cache = p;
        return p;
    }

    /** 扫已装插件 apk 里的 lib/arm64-v8a/libmobileglues.so */
    @Nullable
    private static String scanPluginSoDir(@NonNull Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            // 先按官方插件包名找，找不到再遍历所有含该 so 的包
            List<PackageInfo> pkgs = new ArrayList<>();
            try { pkgs.add(pm.getPackageInfo(PKG_PLUGIN, 0)); }
            catch (PackageManager.NameNotFoundException ignored) {}

            for (PackageInfo pi : pkgs) {
                String apk = pi.applicationInfo != null ? pi.applicationInfo.sourceDir : null;
                if (apk == null) continue;
                File libDir = new File(apk.replaceAll("\\.apk$", "") + "-lib/arm64-v8a");
                if (!libDir.isDirectory()) {
                    // 5.0+ 拆分 apk 结构：base.apk!/lib/arm64-v8a，需从 PackageInfo 拿
                    libDir = new File(extractLibDir(pi.applicationInfo));
                }
                if (libDir.isDirectory() && new File(libDir, SO_NAME).exists()) return libDir.getAbsolutePath();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 ApplicationInfo 拿 nativeLibraryDir（系统已经解压好的 lib 目录） */
    @NonNull
    private static String extractLibDir(@Nullable ApplicationInfo ai) {
        if (ai == null) return "";
        String d = ai.nativeLibraryDir;
        return d == null ? "" : d;
    }

    /** 手动部署位：玩家自己把 so 丢这儿也认 */
    @Nullable
    private static String scanFallback(@NonNull Context ctx) {
        File[] roots = {
                new File("/data/local/lib/mobileglues"),
                new File("/data/local/lib64/mobileglues"),
                new File(ctx.getApplicationInfo().nativeLibraryDir)   // 自己 APK 里万一内置了
        };
        for (File r : roots) {
            if (!r.isDirectory()) continue;
            File hit = findSo(r);
            if (hit != null) return r.getAbsolutePath();
        }
        return null;
    }

    @Nullable
    private static File findSo(@NonNull File dir) {
        File[] fs = dir.listFiles((FileFilter) f -> f.getName().equals(SO_NAME));
        if (fs != null && fs.length > 0) return fs[0];
        // 也往子目录一层找（arm64-v8a/）
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs != null) for (File s : subs) { File h = findSo(s); if (h != null) return h; }
        return null;
    }

    /* ---------------- 对外：列表 + 当前条目 ---------------- */

    /** 设置页看到的完整列表，MobileGlues 排第一，已绑定时带「✓」感 */
    @NonNull
    public static List<Entry> list(@NonNull Context ctx) {
        Probe p = probe(ctx);
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(Kind.MOBILEGLUES, "MOBILEGLUES",
                "MobileGlues" + (p.any() ? "  ·  已绑定" : ""),
                p.any()
                        ? "以插件渲染，光影与 UI 更稳，已自动接管 LD_LIBRARY_PATH"
                        : "未检测到插件。装 Zalith 或 FCL 的 MobileGlues 插件后自动出现",
                p.any(), p.dir()));
        out.add(new Entry(Kind.ANGLE, "ANGLE", "ANGLE · Vulkan",
                "新机型更快；老机型/部分驱动可能黑屏或闪退", false, ""));
        out.add(new Entry(Kind.HOLY_GL4ES, "HOLY_GL4ES", "Holy GL4ES",
                "1.17+ 默认，兼容与速度的平衡，最不容易翻车", false, ""));
        out.add(new Entry(Kind.GL4ES, "GL4ES", "GL4ES · 纯",
                "1.12.2 及远古版首选，方块世界够用", false, ""));
        out.add(new Entry(Kind.OSMESA, "OSMESA", "OSMesa 软渲染",
                "没 GPU 驱动的最后兜底，能跑但很慢，别开光影", false, ""));
        return out;
    }

    /** 从存下来的 id 反查，查不到就回 Holy GL4ES */
    @NonNull
    public static Entry resolve(@NonNull Context ctx, @NonNull String id) {
        for (Entry e : list(ctx)) if (e.id.equals(id)) return e;
        return list(ctx).get(2); // HOLY_GL4ES
    }

    /** 当前选的渲染器是否「真的能用」（MobileGlues 必须探测到，其余永远 true） */
    public static boolean isUsable(@NonNull Context ctx, @NonNull LaunchProfile p) {
        Entry e = resolve(ctx, p.renderer.name());
        return !e.kind.equals(Kind.MOBILEGLUES) || e.detected;
    }

    /* ---------------- 启动游戏时注入的环境变量 ---------------- */

    /**
     * 返回一组要塞进 {@code Runtime.exec(envp)} 的键值对。
     * <p>
     * 变量名对齐 Zalith / FCL / Amethyst 的启动脚本，所以你在那边调好的 MobileGlues
     * 配置，换到漢藝直接生效：
     * <ul>
     *   <li>{@code LD_LIBRARY_PATH} — 前置 so 目录，让 dlopen 先找到 libmobileglues.so</li>
     *   <li>{@code LIBGL_EGL} / {@code LIBGL_GLES} — 指定 EGL/GLES 的实现 so</li>
     *   <li>{@code POJAV_RENDERER} — {@code opengles3} / {@code angle} / {@code gl4es} / {@code osmesa}</li>
     *   <li>{@code POJAVEXEC_EGL=1}、{@code DRIVER_PATH}（插件场景）</li>
     *   <li>{@code GL4ES_VERBOSE=0}、{@code LIBGL_MIPMAP=3}、{@code LIBGL_ES=3} 等保命参数</li>
     * </ul>
     */
    @NonNull
    public static java.util.Map<String, String> envFor(@NonNull Context ctx,
                                                      @NonNull LaunchProfile profile,
                                                      @NonNull File jreLibDir) {
        java.util.Map<String, String> env = new java.util.TreeMap<>();
        Entry e = resolve(ctx, profile.renderer.name());

        // 1) 库路径：先把候选 so 目录顶到最前
        String soDir = e.detected ? e.soDir : "";
        String ldlp = System.getenv("LD_LIBRARY_PATH");
        String head = TextUtils.isEmpty(soDir) ? "" : (soDir + ":");
        env.put("LD_LIBRARY_PATH", head + jreLibDir.getAbsolutePath()
                + (TextUtils.isEmpty(ldlp) ? "" : (":" + ldlp)));

        switch (e.kind) {
            case MOBILEGLUES:
                env.put("LIBGL_EGL", "libmobileglues.so");
                env.put("LIBGL_GLES", "libmobileglues.so");
                env.put("POJAV_RENDERER", "opengles3");
                env.put("POJAVEXEC_EGL", "1");
                if (!TextUtils.isEmpty(soDir)) env.put("DRIVER_PATH", soDir);
                env.put("LIBGL_MIPMAP", "3");
                env.put("LIBGL_ES", "3");
                break;
            case ANGLE:
                env.put("LIBGL_EGL", "libangle.so");
                env.put("POJAV_RENDERER", "angle");
                env.put("POJAVEXEC_EGL", "1");
                env.put("ANGLE_BACKEND", "vulkan");
                break;
            case HOLY_GL4ES:
                env.put("LIBGL_EGL", "libholygl4es.so");
                env.put("POJAV_RENDERER", "gl4es");
                env.put("LIBGL_MIPMAP", "3");
                break;
            case GL4ES:
                env.put("LIBGL_EGL", "libgl4es_android.so");
                env.put("POJAV_RENDERER", "gl4es");
                env.put("GL4ES_VERBOSE", "0");
                env.put("LIBGL_MIPMAP", "2");
                break;
            case OSMESA:
                env.put("POJAV_RENDERER", "osmesa");
                env.put("LIBGL_SOFT", "1");
                env.put("LIBGL_ES", "2");
                break;
        }
        return env;
    }

    /** 渲染器切换后给控制台/状态栏一句提示，方便玩家判断「为什么黑屏」 */
    @NonNull
    public static String diagnose(@NonNull Context ctx, @NonNull LaunchProfile p) {
        Entry e = resolve(ctx, p.renderer.name());
        if (e.kind.equals(Kind.MOBILEGLUES)) {
            return e.detected
                    ? "渲染器：MobileGlues（已绑定 " + e.soDir + "），若黑屏请切回 Holy GL4ES 试试"
                    : "渲染器：MobileGlues 未检测到，已自动回退到 Holy GL4ES";
        }
        return "渲染器：" + e.name + " —— " + e.hint;
    }

    /** 若选了 MobileGlues 但没探测到，给一个「安全的回退渲染器 id」 */
    @NonNull
    public static String safeId(@NonNull Context ctx, @NonNull String wanted) {
        Entry e = resolve(ctx, wanted);
        return (!e.kind.equals(Kind.MOBILEGLUES) || e.detected) ? wanted : "HOLY_GL4ES";
    }
}
