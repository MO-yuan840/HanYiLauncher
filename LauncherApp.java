package net.hanyi.launcher;

import android.app.Application;
import android.os.Environment;
import androidx.annotation.NonNull;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import net.hanyi.launcher.profile.ProfileStore;
import net.hanyi.launcher.controls.TouchOverlayService;
import net.hanyi.launcher.controls.ExternalInputMonitor;
import net.hanyi.launcher.controls.ControlSchemeStore;

import java.io.File;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 全局单例：网络、JSON、根目录、账号仓库。
 * 真做大了建议换成 Dagger/Hilt，这里保持零依赖直白。
 */
public final class LauncherApp extends Application {
    /** 别名：漢藝啟動器全局单例，代码里两处都能认 */
    @NonNull public static LauncherApp get() { return INSTANCE; }

    private static LauncherApp INSTANCE;

    private OkHttpClient http;
    private ObjectMapper om;
    private File baseDir;
    private ProfileStore profiles;
    private ControlSchemeStore schemes;

    /** 控件皮肤仓库（延迟构造，首次用到才 seed 内置方案） */
    @NonNull public ControlSchemeStore schemes() {
        if (schemes == null) schemes = new ControlSchemeStore(this);
        return schemes;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;

        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)   // 下载 jar 慢一点没关系
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        om = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 游戏文件放在内部存储的 XuanZhi/，不上外部存储，避免权限和清理问题
        baseDir = new File(getFilesDir(), "XuanZhi");
        baseDir.mkdirs();
        profiles = new ProfileStore(this);
        schemes();                      // 触发内置三套皮肤 seed
        // 外设监测：插拔键鼠实时生效，触控层据此自动隐身 / 复出
        ExternalInputMonitor.get().start(this);
        ExternalInputMonitor.get().addListener(present -> {
            if (!TouchOverlayService.isGameRunning()) {
                try {
                    Intent i = new Intent(LauncherApp.this, TouchOverlayService.class);
                    if (present) startService(i); else stopService(i);
                } catch (Exception ignored) {}
            }
        });
    }

    /** 字体：京华老宋体外挂。内置思源宋体 HF 保底，这里找不到就返回 null，主题不报错。 */
    @androidx.annotation.Nullable
    public android.graphics.Typeface loadDisplayTypeface() {
        String path = getSharedPreferences("hanyi_ui", 0).getString("display_font_path", "");
        if (!path.isEmpty()) {
            try { return android.graphics.Typeface.createFromFile(path); }
            catch (Exception ignored) {}
        }
        for (java.io.File f : fontSearchRoots()) {
            java.io.File hit = findFont(f);
            if (hit != null) try { return android.graphics.Typeface.createFromFile(hit); } catch (Exception ignored) {}
        }
        return null;
    }

    @NonNull
    private java.io.File[] fontSearchRoots() {
        return new java.io.File[]{
                new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "HanYi/字体"),
                new java.io.File(baseDir, "字体"),
                new java.io.File(getFilesDir(), "字体")
        };
    }

    @androidx.annotation.Nullable
    private java.io.File findFont(@androidx.annotation.NonNull java.io.File dir) {
        if (!dir.isDirectory()) return null;
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return null;
        java.io.File fallback = null;
        for (java.io.File f : fs) {
            String n = f.getName().toLowerCase();
            if (!n.endsWith(".ttf") && !n.endsWith(".otf") && !n.endsWith(".ttc")) continue;
            if (n.contains("京华") || n.contains("老宋") || n.contains("宋体")) return f;
            if (fallback == null) fallback = f;
        }
        return fallback;
    }

    /** 鼠标预设：ZL2·移动 / FCL·点击 */
    @NonNull
    public net.hanyi.launcher.controls.MousePreset mousePreset() {
        try { return net.hanyi.launcher.controls.MousePreset.valueOf(
                getSharedPreferences("hanyi_controls", 0).getString("mouse_preset",
                        net.hanyi.launcher.controls.MousePreset.ZL2_MOVE.name())); }
        catch (IllegalArgumentException e) { return net.hanyi.launcher.controls.MousePreset.ZL2_MOVE; }
    }
    public void setMousePreset(@NonNull net.hanyi.launcher.controls.MousePreset p) {
        getSharedPreferences("hanyi_controls", 0).edit().putString("mouse_preset", p.name()).apply();
    }

    /** 用户手动「永不使用触控」的开关，优先于自动探测 */
    public boolean isTouchForcedOff() {
        return getSharedPreferences("hanyi_controls", 0).getBoolean("touch_forced_off", false);
    }
    @androidx.annotation.Nullable
    public android.graphics.Typeface loadDisplayTypeface() {
    @NonNull public static LauncherApp get() { return INSTANCE; }

    @NonNull public OkHttpClient http() { return http; }
    @NonNull public ObjectMapper om() { return om; }
    @NonNull public File baseDir() { return baseDir; }
    @NonNull public ProfileStore profiles() { return profiles; }

    /** 兼容旧设备拿外部存储根目录的场景（比如你想把游戏放 /sdcard/XuanZhi） */
    @NonNull
    public static File defaultExternalBase() {
        File f = new File(Environment.getExternalStorageDirectory(), "XuanZhi");
        f.mkdirs();
        return f;
    }
}
