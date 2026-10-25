package net.xuanzhi.launcher.controls;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.Nullable;
import net.hanyi.launcher.ui.anim.InkInterpolator;

/**
 * 展开时的那一圈：以召唤点为中心，4 个动作按钮沿圆弧等距弹出，
 * 背景是「墨晕」径向渐变（不是死黑半透），符合宣纸留白。
 * <p>
 * 按钮只有 4 个，直径 56dp，间距足够手指但不占画面：
 *   视角锁定 / 跳跃 / 攻击·使用 / 键盘·更多
 * 其余操作（潜行、背包、ESC）折叠进「更多」面板，平时完全不可见。
 */
public final class RadialMenu extends View {

    private static final int[] ACTIONS = { ACTION_LOOK_LOCK, ACTION_JUMP, ACTION_USE, ACTION_KEYBOARD };
    private static final String[] LABELS = { "锁", "跃", "击", "控" }; // 小篆感单字
    private static final int RADIUS_DP = 96;

    private float pivotY = 0f;
    private float[] cx = new float[4], cy = new float[4];
    private float[] btnAlpha = new float[4];
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int surfaceW, surfaceH;
    private boolean shown = false;

    // 墨色：#1a1410 → 透明，宣纸底上只留一团淡影
    private static final int INK_INNER = 0x4D1A1410;
    private static final int INK_OUTER = 0x00000000;

    public RadialMenu(Context c) { this(c, null); }
    public RadialMenu(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1));
        ring.setColor(0x66B8A48C);   // 绢色描边
        setWillNotDraw(false);
    }

    void setPivotY(float y) { pivotY = y; rebuild(); }

    void expand() {
        setVisibility(VISIBLE);
        shown = true;
        float py = pivotY;
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator s = ObjectAnimator.ofFloat(this, "scaleX", 0.2f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(this, "scaleY", 0.2f, 1f);
        s.setInterpolator(InkInterpolator.get()); sy.setInterpolator(InkInterpolator.get());
        s.setDuration(340); sy.setDuration(340);
        set.playTogether(s, sy);
        // 墨晕扩散 + 按钮逐个浮起
        ObjectAnimator fade = ObjectAnimator.ofFloat(this, "menuAlpha", 0f, 1f);
        fade.setDuration(260); fade.setStartDelay(60);
        set.play(fade);
        set.start();
    }

    void retract(@Nullable Runnable after) {
        shown = false;
        ObjectAnimator a = ObjectAnimator.ofFloat(this, "scaleX", 1f, 0.6f);
        ObjectAnimator b = ObjectAnimator.ofFloat(this, "scaleY", 1f, 0.6f);
        ObjectAnimator c = ObjectAnimator.ofFloat(this, "menuAlpha", 1f, 0f);
        a.setDuration(220); b.setDuration(220); c.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (after != null) after.run();
            }
        });
        AnimatorSet s = new AnimatorSet(); s.playTogether(a, b, c); s.start();
    }

    /** 由 ObjectAnimator 驱动 */
    @SuppressWarnings("unused")
    public void setMenuAlpha(float a) {
        float v = Math.max(0f, Math.min(1f, a));
        for (int i = 0; i < 4; i++) btnAlpha[i] = v * (0.45f + 0.55f * Math.min(1f, (v * 4f - i * 0.6f)));
        ring.setAlpha((int) (0x66 * v));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        surfaceW = w; surfaceH = h;
        rebuild();
    }

    private void rebuild() {
        float cy0 = Math.max(dp(80), Math.min(surfaceH - dp(80), pivotY));
        float cx0 = dp(120);                 // 靠左展开，避开中央视野
        float r = dp(RADIUS_DP);
        for (int i = 0; i < 4; i++) {
            double ang = Math.toRadians(-90 + i * 60); // 从正上起，向右上扇形铺开
            this.cx[i] = cx0 + (float) Math.cos(ang) * r;
            this.cy[i] = cy0 + (float) Math.sin(ang) * r;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown && btnAlpha[0] <= 0.01f) return;
        // 墨晕：以召唤点为中心
        float cy0 = pivotY;
        float cx0 = dp(120);
        RadialGradient g = new RadialGradient(cx0, cy0, dp(150),
                new int[]{ INK_INNER, INK_OUTER }, new float[]{ 0.35f, 1f }, Shader.TileMode.CLAMP);
        ink.setShader(g);
        canvas.drawCircle(cx0, cy0, dp(150), ink);
        // 四个动作钮
        float r = dp(28);
        for (int i = 0; i < 4; i++) {
            if (btnAlpha[i] <= 0.01f) continue;
            ring.setAlpha((int) (0x66 * btnAlpha[i]));
            canvas.drawCircle(cx[i], cy[i], r, ring);
            // 单字：用系统宋体（主题已全局设为思源宋体），比画图标更「简」
            Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
            txt.setColor(0xDD2B2118); txt.setTextSize(dp(18)); txt.setTextAlign(Paint.Align.CENTER);
            txt.setAlpha((int) (0xDD * btnAlpha[i]));
            canvas.drawText(LABELS[i], cx[i], cy[i] + dp(6), txt);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int idx = hit(x, y);
        if (idx >= 0 && e.getActionMasked() == MotionEvent.ACTION_UP) {
            fire(idx);
            ((TouchOverlayService) getContext()).retract(); // 点完自动收回，画面重新干净
            return true;
        }
        return true;
    }

    private int hit(float x, float y) {
        float r = dp(36);
        for (int i = 0; i < 4; i++) {
            float dx = x - cx[i], dy = y - cy[i];
            if (dx * dx + dy * dy <= r * r) return i;
        }
        return -1;
    }

    private void fire(int i) {
        int action = ACTIONS[i];
        switch (action) {
            case ACTION_LOOK_LOCK:
                // 通知 InputBridge 切换「视角锁定 / 自由视角」模式
                InputBridge.get().toggleLookLock();
                break;
            case ACTION_JUMP:      InputBridge.get().tapKey(InputBridge.KEY_SPACE); break;
            case ACTION_USE:       InputBridge.get().tapMouse(InputBridge.BTN_LEFT); break;
            case ACTION_KEYBOARD:  KeyboardPanel.show(getContext()); break;
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    static final int ACTION_LOOK_LOCK = 1, ACTION_JUMP = 2, ACTION_USE = 3, ACTION_KEYBOARD = 4;
}
