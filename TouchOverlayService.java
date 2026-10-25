package net.xuanzhi.launcher.controls;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.appcompat.widget.AppCompatImageView;
import net.hanyi.launcher.R;

/**
 * 游戏内悬浮触控层。挂在独立窗口上，靠 Amethyst 的 input pipe（jre_lwjgl3glfw）
 * 把手势转成鼠标/键盘事件喂给 Minecraft，启动器本身完全不碰渲染线程。
 * <p>
 * 【以简为美 · 不挡画面】三条铁律：
 *   1. 默认只留一条 6dp 的「宣纸边」在屏幕左缘，其余全部透明不可见；
 *   2. 手指从左边缘向右滑 ≥ 40dp 才把摇杆/按键组展开，松手 3 秒无操作再缩回去；
 *   3. 常驻操作只有 3 个：视角拖动区（全屏隐式，不画任何东西）、跳跃/攻击（右下方小圆）、
 *      打开完整按键盘（边缘小篆「控」字，点一下弹出半透面板）。
 * <p>
 * 为什么用 AccessibilityService 而不是 TYPE_APPLICATION_OVERLAY 的普通悬浮窗：
 * Android 10+ 普通悬浮窗拿不到「底层窗口的触摸拦截」语义，且游戏在
 * 前台的沉浸式模式下容易被系统回收；A11y 通道稳定、能跨 activity 存活。
 * 代价：需要在设置里引导用户开「无障碍 / 已安装的应用」权限。
 */
public final class TouchOverlayService extends AccessibilityService {

    private FrameLayout root;
    private View edge;
    private RadialMenu radial;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable hideTask = this::retract;
    private boolean expanded = false;

    @Override
    public void onCreate() {
        super.onCreate();
        buildUi();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = 0;                       // 我们只做触摸转发，不监听事件
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildUi() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        final int edgeW = dp(6);

        root = new FrameLayout(this);
        root.setBackgroundColor(0x00000000);       // 全透明，只有子视图可见

        // ① 左缘召唤条：细长、淡墨，闲置时几乎看不见
        edge = new View(this);
        edge.setBackgroundResource(R.drawable.xz_edge_gradient); // 宣纸渐隐
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(edgeW, (int) (dm.heightPixels * 0.42f));
        ep.leftMargin = 0; ep.topMargin = dm.heightPixels / 2 - ep.height / 2;
        root.addView(edge, ep);

        // ② 径向极简菜单：展开后才出现，3 个动作 + 1 个收起
        radial = new RadialMenu(this);
        radial.setVisibility(View.GONE);
        root.addView(radial, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                                           : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(root, lp);

        // 边缘滑入手势：只在左缘窄带内接管
        root.setOnTouchListener(new EdgeGesture());
    }

    /* -------------------- 展开 / 收回（动画在 RadialMenu 里，见 anim 包） -------------------- */

    void expand(float y) {
        if (expanded) return;
        expanded = true;
        radial.setPivotY(y);
        radial.expand();
        ui.removeCallbacks(hideTask);
        ui.postDelayed(hideTask, 3000);
    }

    void retract() {
        if (!expanded) return;
        expanded = false;
        radial.retract(() -> radial.setVisibility(View.GONE));
    }

    void poke() { // 有操作就续命 3 秒
        if (!expanded) return;
        ui.removeCallbacks(hideTask);
        ui.postDelayed(hideTask, 3000);
    }

    /* -------------------- 把触摸翻译成游戏输入：交给 native pipe -------------------- */

    void injectMotion(int action, float x, float y, int button) {
        // 实际实现：Amethyst 的 jre_lwjgl3glfw 提供 JNI 入口
        //   native void pushTouch(int action, float x, float y, int button);
        // 这里先走 AIDL/本地 socket 转发到运行中的游戏进程，保证启动器崩溃不影响游戏。
        InputBridge.get().dispatch(action, x, y, button);
    }

    /* -------------------- 边缘手势识别 -------------------- */

    private final class EdgeGesture implements View.OnTouchListener {
        private float startX = -1, startY = -1;
        private boolean armed = false;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 只在左缘窄带内才认作「召唤手势」
                    if (x < dp(28)) { startX = x; startY = y; armed = true; }
                    else armed = false;
                    if (expanded) poke();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (armed && startX >= 0 && x - startX > dp(40)) {
                        expand(startY);
                        armed = false;
                    }
                    if (expanded && e.getPointerCount() == 1)
                        injectMotion(MotionEvent.ACTION_MOVE, x, y, 0);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (expanded) injectMotion(MotionEvent.ACTION_UP, x, y, 0);
                    startX = -1; armed = false;
                    break;
            }
            return expanded; // 展开后独占触摸，否则透传给游戏视角区
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { /* 不用 */ }

    @Override
    public void onInterrupt() { /* 系统回收时保持安静 */ }

    @Override
    public boolean onUnbind(Intent intent) { return super.onUnbind(intent); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
