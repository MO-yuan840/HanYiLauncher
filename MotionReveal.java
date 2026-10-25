package net.xuanzhi.launcher.ui.anim;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 页面与控件的「墨晕式」出现/收起：位移极小（12dp）、时长偏长，看起来是浮起来而不是弹。 */
public final class MotionReveal {
    private MotionReveal() {}

    public static void enter(View v) {
        v.setAlpha(0f); v.setTranslationY(dp(v, 12));
        v.animate().alpha(1f).translationY(0f).setDuration(380)
                .setInterpolator(InkInterpolator.get())
                .setListener(null).start();
    }

    public static void exit(View v, Runnable after) {
        v.animate().alpha(0f).translationY(dp(v, -8)).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) { after.run(); }
                }).start();
    }

    private static float dp(View v, int d) {
        return d * v.getResources().getDisplayMetrics().density;
    }
}
