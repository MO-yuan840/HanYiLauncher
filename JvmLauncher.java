package net.xuanzhi.launcher.launch;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.hanyi.launcher.auth.Account;
import net.hanyi.launcher.jre.JreRuntime;
import net.hanyi.launcher.meta.VersionMeta;
import net.hanyi.launcher.profile.LaunchProfile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 真正把游戏跑起来的一层。
 *
 * 思路（和 Pojav/Amethyst 一致，不用 JNI 直接 JNI_CreateJavaVM，而是 fork 一个
 * 独立进程跑 `java` 命令）：
 *   1. 组出 classpath：client.jar + libraries/*.jar + natives 里的解压产物
 *   2. 把 -Xmx / -Dorg.lwjgl.* / -Dpojav.* / 渲染后端开关拼进 JVM 参数
 *   3. 拼 game arguments（--username --uuid --accessToken --assetIndex ...）
 *   4. 用 Runtime.exec(javaCmd, envp, gameDir) 拉起，envp 里塞好
 *      LD_LIBRARY_PATH=.../jre/lib/aarch64:.../natives:...
 *   5. 开两个线程吞 stdout/stderr，通过 LogPipe 回给 UI（ConsoleActivity）
 *
 * 为什么不用 JNI 内嵌：Android 主进程里 JNI_CreateJavaVM 一旦崩会把整个 app 带走，
 * 而且 ClassLoader、信号处理、文件描述符都会和 ART 打架。独立进程 = 稳。
 */
public final class JvmLauncher {

    public interface LogPipe {
        void stdout(@NonNull String line);
        void stderr(@NonNull String line);
        void exited(int code);
    }

    private static final String TAG = "JvmLauncher";

    private final JreRuntime jre;
    private final File gameDir;
    private final LogPipe pipe;
    private Process process;

    public JvmLauncher(@NonNull JreRuntime jre, @NonNull File gameDir, @NonNull LogPipe pipe) {
        this.jre = jre;
        this.gameDir = gameDir;
        this.pipe = pipe;
    }

    /** 拼出完整命令行。返回 null 表示前置条件不满足（JRE/账号/版本缺）。 */
    @Nullable
    public List<String> buildCommand(@NonNull VersionMeta meta, @NonNull LaunchProfile profile, @NonNull Account account) {
        if (!jre.isReady()) return null;

        List<String> cmd = new ArrayList<>();
        cmd.add(jre.javaBin() == null ? "java" : jre.javaBin().getAbsolutePath());

        // ---- JVM 参数 ----
        for (String a : profile.baseJvmArgs()) cmd.add(a);
        for (String f : profile.renderer.toJvmFlags()) cmd.add(f);

        // lwjgl 找 natives 的路径：我们的 natives 目录 + JRE 自己的 lib
        File natives = new File(gameDir, "natives");
        cmd.add("-Djava.library.path=" + natives.getAbsolutePath()
                + ":" + jre.libDir().getAbsolutePath()
                + ":" + new File(jre.jreDir(), "lib").getAbsolutePath());

        // 让游戏认为自己在 "linux" 上，避免某些版本按 os.name 走 Windows/Mac 分支
        cmd.add("-Dos.name=Linux");
        cmd.add("-Dos.arch=aarch64");
        cmd.add("-Duser.home=" + gameDir.getAbsolutePath());

        // 启动器标识（游戏主菜单左下角会显示）
        cmd.add("-Dminecraft.launcher.brand=XuanZhi");
        cmd.add("-Dminecraft.launcher.version=0.1.0");

        // 日志：把 log4j/xml 指到我们下载的那份配置
        File logCfg = new File(gameDir, "assets/log_configs/" + meta.id + ".xml");
        if (logCfg.exists()) {
            cmd.add("-Dlog4j.configurationFile=" + logCfg.getAbsolutePath());
        }

        // 用户自定义 JVM 参数（放最后，允许覆盖前面的 -Xmx）
        if (!profile.extraJvmArgs.trim().isEmpty()) {
            for (String t : profile.extraJvmArgs.trim().split("\\s+")) if (!t.isEmpty()) cmd.add(t);
        }

        // ---- classpath ----
        StringBuilder cp = new StringBuilder();

        // 加载器入口 jar 排在 client.jar 之前：mainClass 会是 fabric/forge 的注入器
        if (!profile.getLoader().isEmpty()) {
            File lj = pickLoaderJar(profile, vDir, libs);
            if (lj != null && lj.exists()) cp.append(lj.getAbsolutePath());
        }
        File vDir = new File(gameDir, "versions/" + meta.id);
        cp.append(new File(vDir, meta.id + ".jar").getAbsolutePath());
        File libs = new File(gameDir, "libraries");
        if (libs.exists()) collectJars(libs, cp);
        cmd.add("-cp");
        cmd.add(cp.toString());

        // ---- 主类 ----
        cmd.add(meta.mainClass);

        // ---- game arguments ----
        if (meta.arguments != null && meta.arguments.game != null && !meta.arguments.game.isEmpty()) {
            // 1.13+ 结构化参数：把 ${...} 占位符替换掉
            for (Object node : meta.arguments.game) {
                if (node instanceof String) cmd.add(replace((String) node, meta, profile, account));
            }
        } else if (!meta.minecraftArguments.isEmpty()) {
            // 1.12- 单行式
            for (String t : meta.legacyArgs(account.authPlayerName(), account.authUuid(),
                    account.authToken(), new File(gameDir, "assets").getAbsolutePath())) {
                cmd.add(t);
            }
        } else {
            // 兜底：绝大多数版本靠这些
            cmd.add("--username"); cmd.add(account.authPlayerName());
            cmd.add("--uuid");     cmd.add(account.authUuid());
            cmd.add("--accessToken"); cmd.add(account.authToken());
            cmd.add("--assetIndex"); cmd.add(meta.assetIndex == null ? "legacy" : meta.assetIndex.id);
            cmd.add("--assetsDir"); cmd.add(new File(gameDir, "assets").getAbsolutePath());
            cmd.add("--version");  cmd.add(meta.id);
            cmd.add("--versionType"); cmd.add("XuanZhi");
            cmd.add("--gameDir");  cmd.add(gameDir.getAbsolutePath());
        }

        // 加载器约定：显式交游戏目录与版本槽，避免各加载器各自猜路径
        if (!profile.getLoader().isEmpty()) {
            cmd.add("--gameDir"); cmd.add(gameDir.getAbsolutePath());
            cmd.add("--version"); cmd.add(profile.getVersionSlot());
        }

        // 用户自定义 game 参数
        if (!profile.extraGameArgs.trim().isEmpty()) {
            for (String t : profile.extraGameArgs.trim().split("\\s+")) if (!t.isEmpty()) cmd.add(t);
        }
        return cmd;
    }

    @NonNull
    private String replace(@NonNull String t, @NonNull VersionMeta meta,
                           @NonNull LaunchProfile profile, @NonNull Account account) {
        return t
            .replace("${auth_player_name}", account.authPlayerName())
            .replace("${auth_uuid}", account.authUuid())
            .replace("${auth_access_token}", account.authToken())
            .replace("${user_type}", account.isOnline() ? "msa" : "legacy")
            .replace("${version_name}", meta.id)
            .replace("${version_type}", "XuanZhi")
            .replace("${game_directory}", gameDir.getAbsolutePath())
            .replace("${assets_root}", new File(gameDir, "assets").getAbsolutePath())
            .replace("${assets_index_name}", meta.assetIndex == null ? "legacy" : meta.assetIndex.id)
            .replace("${resolution_width}", "0")
            .replace("${resolution_height}", "0");
    }

    private void collectJars(@NonNull File dir, @NonNull StringBuilder cp) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectJars(f, cp);
            else if (f.getName().endsWith(".jar")) {
                if (cp.length() > 0) cp.append(File.pathSeparator);
                cp.append(f.getAbsolutePath());
            }
        }
    }

    /** 启动。返回 false 表示根本没起来。 */
    public synchronized boolean start(@NonNull VersionMeta meta, @NonNull LaunchProfile profile, @NonNull Account account) {
        if (process != null) return false;
        List<String> cmd = buildCommand(meta, profile, account);
        if (cmd == null) return false;

        try {
            Log.i(TAG, ">> " + String.join(" ", cmd));
            pipe.stdout("# XuanZhi: JVM 启动中…");
            pipe.stdout("# " + String.join(" ", cmd));

            // 渲染器环境变量（MobileGlues 自动绑定就发生在这里）+ 用户自定义环境覆盖
            java.util.Map<String, String> envMap = new java.util.TreeMap<>();
            for (String kv : jre.envp()) {
                int eq = kv.indexOf('=');
                if (eq > 0) envMap.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
            try {
                envMap.putAll(net.hanyi.launcher.launch.RendererRegistry.envFor(
                        net.hanyi.launcher.LauncherApp.get(), profile, jre.libDir()));
            } catch (Exception re) {
                pipe.stderr("# 渲染器探测跳过: " + re.getMessage());
            }
            if (!profile.extraEnv.isEmpty()) envMap.putAll(profile.extraEnv); // 高级：允许手动覆盖
            if (!profile.renderer.name().equals("MOBILEGLUES")
                    && !net.hanyi.launcher.launch.RendererRegistry.isUsable(
                            net.hanyi.launcher.LauncherApp.get(), profile)) {
                pipe.stderr("# 警告：所选渲染器不可用，自动回退到 HOLY_GL4ES");
                profile.renderer = net.hanyi.launcher.profile.LaunchProfile.Renderer.HOLY_GL4ES;
            }
            List<String> envList = new ArrayList<>();
            for (java.util.Map.Entry<String, String> e : envMap.entrySet())
                envList.add(e.getKey() + "=" + e.getValue());
            pipe.stdout("# 渲染器：" + profile.renderer.label
                    + (profile.renderer == net.hanyi.launcher.profile.LaunchProfile.Renderer.MOBILEGLUES
                        ? (net.hanyi.launcher.launch.RendererRegistry.resolve(
                                net.hanyi.launcher.LauncherApp.get(), "MOBILEGLUES").detected
                                ? "（已绑定）" : "（未探测到，已回退）") : ""));
            String[] envp = envList.toArray(new String[0]);
            process = Runtime.getRuntime().exec(cmd.toArray(new String[0]), envp, gameDir);

            // 吞输出，送到 UI
            drain(process.getInputStream(), false);
            drain(process.getErrorStream(), true);

            // 等待进程结束（另起线程，不阻塞调用方）
            new Thread(() -> {
                try {
                    int c = process.waitFor();
                    pipe.stdout("# XuanZhi: 进程退出，code=" + c);
                    pipe.exited(c);
                } catch (InterruptedException e) {
                    pipe.stderr("# XuanZhi: 等待被中断");
                    pipe.exited(-1);
                }
            }, "jvm-waiter").start();

            return true;
        } catch (IOException e) {
            pipe.stderr("# XuanZhi 启动失败: " + e.getMessage());
            Log.e(TAG, "launch failed", e);
            return false;
        }
    }

    public synchronized void stop() {
        if (process != null) {
            process.destroy();
            try { process.waitFor(); } catch (InterruptedException ignore) {}
            process = null;
        }
    }

    private void drain(@NonNull java.io.InputStream is, boolean isErr) {
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (isErr) pipe.stderr(line); else pipe.stdout(line);
                }
            } catch (IOException e) {
                if (!"Stream closed".equals(e.getMessage())) pipe.stderr("# reader: " + e.getMessage());
            }
        }, isErr ? "jvm-err" : "jvm-out");
    }

    /**
     * 定位加载器入口 jar。优先用 LoaderInstaller 写回的 loaderJar，
     * 找不到就按标记扫 libraries / versions 目录。
     */
    @Nullable
    private File pickLoaderJar(@NonNull LaunchProfile profile, @NonNull File vDir, @NonNull File libs) {
        if (profile.getLoaderJar() != null && !profile.getLoaderJar().isEmpty())
            return new File(profile.getLoaderJar());
        final String mark;
        switch (profile.getLoader()) {
            case "fabric": mark = "fabric-loader"; break;
            case "quilt":  mark = "quilt-loader"; break;
            case "neoforge": mark = "neoforge"; break;
            default:       mark = "forge-"; break;
        }
        File[] hits = libs.listFiles((d, n) -> n.toLowerCase().contains(mark) && n.endsWith(".jar"));
        if (hits != null && hits.length > 0) {
            File best = hits[0];
            for (File f : hits) if (f.lastModified() > best.lastModified()) best = f;
            return best;
        }
        if (vDir.exists()) {
            File[] vh = vDir.listFiles((d, n) -> n.toLowerCase().contains(mark) && n.endsWith(".jar"));
            if (vh != null && vh.length > 0) return vh[0];
        }
        return null;
    }
}
