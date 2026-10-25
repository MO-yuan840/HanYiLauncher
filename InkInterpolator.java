package net.xuanzhi.launcher.ui.anim;

import android.animation.TimeInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * 「水墨」缓动：起手极轻、中段加速、收尾像一滴墨在宣纸上洇开那样慢慢停住。
 * <p>
 * 这不是标准贝塞尔，而是 (1 - (1-x)^3.2) 叠一层极淡的正弦修正，
 * 比 PCL2 默认的三次缓出更「沉」，比弹簧更「静」—— 中式 UI 要的是留白感，
 * 不是弹来弹去。
 * <p>
 * 用法：view.animate().setInterpolator(InkInterpolator.get()).translationX(...);
 *       ObjectAnimator.ofFloat(..., "alpha", 0,1).setInterpolator(InkInterpolator.get());
 */
public final class InkInterpolator implements TimeInterpolator {

    private static final InkInterpolator INST = new InkInterpolator();
    private static final DecelerateInterpolator FALLBACK = new DecelerateInterpolator(2.2f);

    public static InkInterpolator get() { return INST; }

    /** t ∈ [0,1] → 进度。三次缓出 3.2 次 + 墨韵修正 */
    @Override
    public float getInterpolation(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        // 核心：指数收束
        float x = 1f - t;
        float core = 1f - (x * x * x * (float) Math.pow(x, 0.2));
        // 墨韵：中段微抬，让位移「带一点惯性」但不回弹
        float ink = (float) (0.08 * Math.sin(Math.PI * t));
        float v = core + ink * (1f - t);
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 更重的版本：用于整页切换，像卷轴徐徐展开 */
    public static TimeInterpolator scroll() {
        return t -> {
            float x = 1f - t;
            return 1f - x * x * x * x;
        };
    }

    /** 极轻：用于按钮按下的回弹反馈，几乎看不出来，但手指会觉得「接住了」 */
    public static TimeInterpolator press() {
        return t -> (float) (1f - Math.pow(1f - t, 2.4f));
    }

    /** 让系统默认动画也走墨韵（可选，在 Application 里装） */
    public static void installGlobally() {
        // 通过反射替换 sQuinticInterpolator 属于黑魔法，这里只暴露接口，不动系统。
    }
}
