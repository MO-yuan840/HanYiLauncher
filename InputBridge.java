package net.hanyi.launcher.controls;

import android.util.Log;
import net.hanyi.launcher.BuildConfig;

/**
 * 输入桥：Java 侧只定义干净 API，真活儿在 {@code jni/input_bridge.c}，
 * 事件通过 Amethyst / Zalith 的 input pipe（jre_lwjgl3glfw 内部那段 pipe）喂给游戏进程。
 * <p>
 * 为什么不直接用 MotionEvent 塞？游戏跑在 {@code Runtime.exec} 起的独立 JVM 里，
 * 那个进程的 GLFW 窗口只认 pipe 里的 input_event。启动器就算被系统杀掉，游戏里的触控也不抽风。
 * <p>
 * 两个鼠标语义（见 {@link MousePreset}）：
 * <ul>
 *   <li>{@link MousePreset#ZL2_MOVE}：走 {@link #pan(float, float)}，发<b>相对增量</b>，拖=转视角。</li>
 *   <li>{@link MousePreset#FCL_CLICK}：走 {@link #moveAbs(float, float)}，发<b>绝对坐标</b>，点哪到哪。</li>
 * </ul>
 * 若 libinputbridge.so 没编进来（没接 Amethyst / Zalith 的 aar），这里安静降级成无操作，不崩。
 */
public final class InputBridge {

    private static final String TAG = "HanYi:Input";
    private static final InputBridge INST = new InputBridge();
    private boolean nativeOk = false;

    public static InputBridge get() { return INST; }

    private InputBridge() {
        try {
            System.loadLibrary("inputbridge");
            nativeOk = true;
        } catch (UnsatisfiedLinkError e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "libinputbridge.so 未载入：触控降级为无操作（接 Amethyst/Zalith 的 jre_lwjgl3glfw 即可生效）");
            nativeOk = false;
        }
    }

    /** 原生输入通路是否可用。UI 层据此决定是否开放「悬浮触控」开关。 */
    public boolean isAvailable() { return nativeOk; }

    /* ---------- 触摸（按下 / 移动 / 抬起），坐标由对端解释绝对或相对 ---------- */

    /** action: 0=down 1=move 2=up；button: 0=无 1=左键 2=右键 4=中键 */
    public void dispatch(int action, float x, float y, int button) {
        if (!nativeOk) return;
        nativeDispatch(action, x, y, button);
    }

    /**
     * FCL·点击：把光标<b>瞬移</b>到 (x,y) 再 click。
     * 协议里 flags 位 0 置 1，表示这是绝对 warp，对端不要当成增量累加。
     */
    public void moveAbs(float x, float y) {
        if (!nativeOk) return;
        nativeMoveAbs(x, y);
    }

    /**
     * ZL2·移动：dx/dy 是手指划过的距离，转成视角增量。
     * 对端看到 flags=0 就按相对位移处理。
     */
    public void pan(float dx, float dy) {
        if (!nativeOk) return;
        nativePan(dx, dy);
    }

    /* ---------- 键盘 ---------- */

    public void tapKey(int glfwKey) {
        if (!nativeOk) return;
        nativeKey(glfwKey, true);
        nativeKey(glfwKey, false);
    }

    public void holdKey(int glfwKey, boolean down) {
        if (!nativeOk) return;
        nativeKey(glfwKey, down);
    }

    public void typeText(@androidx.annotation.NonNull String text) {
        if (!nativeOk) return;
        nativeText(text);
    }

    /* ---------- 鼠标按键 / 滚轮 ---------- */

    /** 单击一次：down → up，坐标沿用上次 moveAbs / pan 的位置 */
    public void tapMouse(int button) {
        if (!nativeOk) return;
        nativeClick(button);
    }

    public void scroll(float dy) { if (nativeOk) nativeScroll(dy); }
    public void setSensitivity(float sens) { if (nativeOk) nativeSetSens(sens); }
    public void toggleLookLock() { if (nativeOk) nativeToggleLookLock(); }

    /* ---------- native ---------- */

    private static native void nativeDispatch(int action, float x, float y, int button);
    private static native void nativeMoveAbs(float x, float y);
    private static native void nativePan(float dx, float dy);
    private static native void nativeKey(int glfwKey, boolean down);
    private static native void nativeText(String text);
    private static native void nativeClick(int button);
    private static native void nativeScroll(float dy);
    private static native void nativeSetSens(float s);
    private static native void nativeToggleLookLock();

    /* ---------- GLFW 键位常量（与 native 侧约定一致） ---------- */
    public static final int KEY_SPACE = 32, KEY_ESCAPE = 256, KEY_ENTER = 257,
            KEY_TAB = 258, KEY_SHIFT = 340, KEY_CTRL = 341, KEY_ALT = 342,
            KEY_E = 69, KEY_Q = 81, KEY_R = 82, KEY_F = 70, KEY_B = 66;
    public static final int BTN_LEFT = 1, BTN_RIGHT = 2, BTN_MIDDLE = 4;
}
