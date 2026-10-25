package net.xuanzhi.launcher.jre;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.hanyi.launcher.BuildConfig;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * JRE 运行时管理器。
 *
 * 为什么这么麻烦：Android 上没法直接 `new ProcessBuilder("java")`。
 * 必须带一个为 Android/ARM 交叉编译的 OpenJDK（Pojav/Amethyst 用的就是
 * android-openjdk-build-multiarch 的产物），它本质是一堆 .so + 一堆 .jar。
 *
 * 目录约定（和 Amethyst/Pojav 一致，方便你直接复用它的 CI 产物）：
 *   <base>/jre/
 *     ├── bin/java                    # 其实是 jli 启动器，实际靠 libjli.so
 *     ├── lib/
 *     │   ├── aarch64/
 *     │   │   ├── libjvm.so           # HotSpot，最关键
 *     │   │   ├── libjava.so
 *     │   │   ├── libjli.so
 *     │   │   ├── libnet.so / libnio.so / libzip.so ...
 *     │   │   └── libgl4es_android.so  # 渲染翻译层
 *     │   └── ext/ server/ tzdb.dat security/ ...
 *     └── release
 *
 * 首次启动要把 assets/components/jre/{universal.tar.xz,bin-arm64.tar.xz}
 * 解出来（这里给 xz 解压的占位 + 普通 zip 解压的通用实现）。
 */
public final class JreRuntime {

    private static final String JRE_DIR   = "jre";
    private static final String BIN_SUB   = "lib/aarch64";      // 与 AndroidManifest meta-data 一致
    private static final String JVM_SO    = "libjvm.so";
    private static final String JLI_SO    = "libjli.so";

    private final File base;

    public JreRuntime(@NonNull File baseDir) { this.base = baseDir; }

    @NonNull public File jreDir() { return new File(base, JRE_DIR); }
    @NonNull public File libDir() { return new File(jreDir(), BIN_SUB); }

    /** 兼容别名：isInstalled 语义更直白，LoaderInstaller / ViewModel 都走这个。 */
    public boolean isInstalled() { return isReady(); }

    /**
     * 同步解压 JRE。已经在位就立刻返回。
     * 这是「下载游戏自动下载 Java」的落点：任何需要 JVM 的动作（装加载器、跑游戏、
     * 装模组时跑 installer jar）先调一次这个，用户完全无感。
     * 阻塞调用，请在子线程执行；进度通过 sink 回传（0-100）。
     */
    public void installIfMissing(@NonNull ProgressSink sink) throws IOException {
        if (isInstalled()) { sink.on(100, "JRE 已就位"); return; }
        sink.on(0, "正在铺展 Java 运行境…");
        extract(sink);   // 既有逻辑已在 extract() 里按阶段报进度
        if (!isInstalled()) throw new IOException("JRE 部署失败，请到「设置→运行境」重试");
    }

    /** 进度回调：percent=-1 表示纯日志心跳 */
    public interface ProgressSink {
        default void on(int percent) {}
        default void on(int percent, String msg) { on(percent); }
    }

    /** 是否已经解压就绪：libjvm.so 存在就算能跑。 */
    public boolean isReady() {
        File jvm = new File(libDir(), JVM_SO);
        if (jvm.exists() && jvm.length() > 1_000_000L) return true;
        // 兼容部分构建把 so 平铺在 lib/ 下
        return new File(jreDir(), "lib/" + JVM_SO).exists();
    }

    /** java 启动器可执行文件（有些构建叫 java，有些叫 jli，返回可用那个）。 */
    @Nullable
    public File javaBin() {
        File b = new File(jreDir(), "bin/java");
        if (b.canExecute() || b.exists()) return b;
        b = new File(jreDir(), "bin/javaw");
        return b.exists() ? b : null;
    }

    /**
     * 启动 JVM 前必须设置的环境变量。
     * LD_LIBRARY_PATH 把 libjvm 所在的目录前置，否则 dlopen 找不到依赖链。
     */
    @NonNull
    public String[] envp() {
        String lib = libDir().getAbsolutePath();
        String javaHome = jreDir().getAbsolutePath();
        return new String[]{
                "JAVA_HOME=" + javaHome,
                "LD_LIBRARY_PATH=" + lib + ":" + System.getenv("LD_LIBRARY_PATH"),
                "PATH=" + javaHome + "/bin:" + System.getenv("PATH"),
                "POJAV_JRE_PATH=" + javaHome,
                "POJAV_RENDERER=" + System.getenv("POJAV_RENDERER"), // 由 Launcher 按 profile 注入
                "TMPDIR=" + new File(base, "tmp").getAbsolutePath()
        };
    }

    /** 解压 assets 里的 JRE 分卷到 baseDir。xz 需要 xz-decoder，这里先给 zip 兜底实现。 */
    public void unpackIfNeeded(@NonNull Context ctx, @NonNull Progress p) throws IOException {
        if (isReady()) { p.onStatus("JRE 已就绪"); return; }

        File out = jreDir();
        out.mkdirs();
        new File(base, "tmp").mkdirs();

        String[] vols = { "universal.zip", "bin-arm64.zip" };
        for (String vol : vols) {
            String name = "components/jre/" + vol;
            try (InputStream is = ctx.getAssets().open(name)) {
                p.onStatus("解压 JRE：" + vol);
                unzip(is, out, p);
            } catch (IOException e) {
                // assets 里没放这个分卷不算致命，两个都没有才是真缺
                p.onStatus("跳过 " + vol + "（未打包进 APK）");
            }
        }

        // 保底：让 libjvm.so 有可执行权限（extractNativeLibs=true 一般已处理）
        chmodR(libDir());

        if (!isReady()) {
            throw new IOException("JRE 解压后仍找不到 libjvm.so。\n"
                + "请把 android-openjdk-build-multiarch 的 jre8-pojav 产物\n"
                + "universal + bin-arm64 分卷解压/打包进 assets/components/jre/。\n"
                + "搜索路径：" + libDir().getAbsolutePath());
        }
    }

    /** 通用 zip 解压（JRE 分卷若是 tar.xz，需要在 build.gradle 里加 xz-decoder 并替换此处）。 */
    private static void unzip(@NonNull InputStream in, @NonNull File dest, @NonNull Progress p) throws IOException {
        byte[] buf = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if (e.isDirectory()) { f.mkdirs(); zis.closeEntry(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                }
                zis.closeEntry();
            }
        }
    }

    private static void chmodR(@NonNull File dir) {
        if (!dir.exists()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".so")) f.setExecutable(true, false);
            else if (f.isDirectory()) chmodR(f);
        }
    }

    /** 判断当前是不是 64 位 ARM —— 决定加载哪个 abi 的 JRE。 */
    public static boolean isArm64() {
        return "aarch64".equalsIgnoreCase(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
    }

    public interface Progress {
        void onStatus(@NonNull String msg);
    }
}
