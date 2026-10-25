package net.xuanzhi.launcher.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import net.hanyi.launcher.BuildConfig;
import net.hanyi.launcher.LauncherApp;
import net.hanyi.launcher.auth.Account;
import net.hanyi.launcher.jre.JreRuntime;
import net.hanyi.launcher.launch.JvmLauncher;
import net.hanyi.launcher.controls.TouchOverlayService;
import net.hanyi.launcher.launch.VersionInstaller;
import net.hanyi.launcher.meta.MetaRepository;
import net.hanyi.launcher.meta.VersionManifest;
import net.hanyi.launcher.meta.VersionMeta;
import net.hanyi.launcher.profile.LaunchProfile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面的状态机。UI 只订阅 LiveData，所有 IO 都在这个 VM 里跑。
 * 状态：IDLE -> PREPARE_JRE -> (FETCH_META -> INSTALL -> LAUNCHING) -> RUNNING / ERROR
 */
public final class LaunchViewModel extends AndroidViewModel {

    public enum Phase { IDLE, PREPARE_JRE, FETCH_META, INSTALL, LAUNCHING, RUNNING, ERROR, STOPPED }

    public static class State {
        @NonNull Phase phase = Phase.IDLE;
        @NonNull String status = "";
        @NonNull String detail = "";
        boolean progressIndeterminate;
        long progressDone, progressTotal;
    }

    private final MutableLiveData<State> state = new MutableLiveData<>(new State());
    private final MutableLiveData<String> logLine = new MutableLiveData<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private JreRuntime jre;
    private JvmLauncher launcher;
    private MetaRepository metaRepo;
    private VersionMeta resolvedMeta;
    private LaunchProfile activeProfile = new LaunchProfile();

    public LaunchViewModel(@NonNull Application app) { super(app); }

    @NonNull public LiveData<State> state() { return state; }
    @NonNull public LiveData<String> log() { return logLine; }

    private void emit(@NonNull Phase p, @NonNull String s, @NonNull String d) {
        State st = new State();
        st.phase = p; st.status = s; st.detail = d;
        st.progressIndeterminate = true;
        state.setValue(st);
    }
    private void emitProgress(@NonNull Phase p, @NonNull String s, long done, long total) {
        State st = new State();
        st.phase = p; st.status = s; st.detail = done + " / " + (total < 0 ? "?" : total);
        st.progressDone = done; st.progressTotal = total; st.progressIndeterminate = total <= 0;
        state.setValue(st);
    }

    /** 初始化：检查 JRE 是否就绪，顺带加载当前 profile。 */
    public void init() {
        File base = LauncherApp.get().baseDir();
        jre = new JreRuntime(base);
        launcher = new JvmLauncher(jre, base, new JvmLauncher.LogPipe() {
            @Override public void stdout(@NonNull String l) { logLine.postValue(l); }
            @Override public void stderr(@NonNull String l) { logLine.postValue("! " + l); }
            @Override public void exited(int c) {
                TouchOverlayService.setGameRunning(false);
            try { net.hanyi.launcher.LauncherApp.get().stopService(
                    new Intent(net.hanyi.launcher.LauncherApp.get(), TouchOverlayService.class)); }
            catch (Exception ignored) {}
            emit(c == 0 ? Phase.STOPPED : Phase.ERROR,
                        c == 0 ? "游戏已退出" : "游戏异常退出 code=" + c, "");
            }
        });
        metaRepo = new MetaRepository(LauncherApp.get().http(), LauncherApp.get().om(), base);

        if (jre.isReady()) {
            Account a = LauncherApp.get().profiles().active();
            emit(Phase.IDLE, "就绪", a == null ? "离线模式" : ("账号: " + a.displayName));
        } else {
            emit(Phase.PREPARE_JRE, "首次运行：需要解压 JRE（约 80MB）", "点「启动游戏」自动开始");
        }
    }

    /** 点「启动游戏」的入口：按阶段一路往下走。 */
    public void launch(@NonNull LaunchProfile profile, @NonNull Account account) {
        activeProfile = profile;
        io.execute(() -> {
            try {
                // 1) JRE
                if (!jre.isReady()) {
                    emit(Phase.PREPARE_JRE, "正在解压 JRE…", "第一次比较慢，请耐心等");
                    jre.unpackIfNeeded(getApplication().getApplicationContext(), m -> {
                        State st = state.getValue(); if (st == null) st = new State();
                        st.phase = Phase.PREPARE_JRE; st.status = m; st.progressIndeterminate = true;
                        state.postValue(st);
                    });
                }
                // 2) 版本元数据
                emit(Phase.FETCH_META, "拉取版本清单…", profile.versionId);
                if (metaRepo == null) metaRepo = new MetaRepository(LauncherApp.get().http(), LauncherApp.get().om(), LauncherApp.get().baseDir());
                VersionManifest.Entry entry = profile.versionId.isEmpty()
                        ? metaRepo.latestRelease()
                        : metaRepo.find(profile.versionId);
                if (entry == null) throw new IOException("找不到版本: " + profile.versionId);
                resolvedMeta = metaRepo.version(entry);

                // 3) 安装（client.jar + libs + assets）
                emit(Phase.INSTALL, "安装 " + entry.id, "检查本地文件…");
                File gameDir = new File(LaunchProfile.defaultGameDir(LauncherApp.get().baseDir().getAbsolutePath(), entry.id));
                gameDir.mkdirs();
                new VersionInstaller(LauncherApp.get().http(), LauncherApp.get().om(), gameDir)
                        .install(entry.id, entry.url, BuildConfig.ASSET_HOST, new VersionInstaller.Listener() {
                    @Override public void onStage(@NonNull String s) { emit(Phase.INSTALL, s, ""); }
                    @Override public void onProgress(@NonNull String t, long d, long tt) { emitProgress(Phase.INSTALL, t, d, tt); }
                    @Override public void onDone(@NonNull VersionMeta m) { resolvedMeta = m; }
                    @Override public void onError(@NonNull String t, @NonNull IOException e) { emit(Phase.ERROR, "下载失败: " + t, e.getMessage()); }
                });

                // 4) 点火
                emit(Phase.LAUNCHING, "正在启动 JVM…", resolvedMeta.mainClass);
                boolean ok = launcher.start(resolvedMeta, activeProfile, account);
                if (!ok) throw new IOException("JVM 未能启动，检查日志与 JRE 完整性");
                emit(Phase.RUNNING, "运行中 · " + entry.id, "切到控制台看实时输出");
                TouchOverlayService.setGameRunning(true);
                net.hanyi.launcher.LauncherApp.get().schemes().current(); // 预加载皮肤
                // 外设已接管时不会显示触控层；否则按当前鼠标预设激活
                try {
                    Intent i = new Intent(net.hanyi.launcher.LauncherApp.get(), TouchOverlayService.class);
                    net.hanyi.launcher.LauncherApp.get().startService(i);
                } catch (Exception ignored) {}

            } catch (Exception e) {
                emit(Phase.ERROR, "启动失败", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                logLine.postValue("# ERROR: " + e.getMessage());
            }
        });
    }

    public void stop() {
        if (launcher != null) launcher.stop();
        emit(Phase.STOPPED, "已停止", "");
        TouchOverlayService.setGameRunning(false);
    }

    @Override
    protected void onCleared() {
        io.shutdownNow();
        if (launcher != null) launcher.stop();
        super.onCleared();
    }
}
