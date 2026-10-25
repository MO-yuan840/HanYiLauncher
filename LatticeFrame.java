package net.xuanzhi.launcher.ui.window;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import net.hanyi.launcher.R;

/**
 * 「隔窗」边框容器 —— 中式审美的骨架。
 * <p>
 * 以简为美的实现：不画满花窗，只画四角与四边中点的「冰裂纹」短折线与一道细描边，
 * 中间大片留白交给宣纸底。四个角是「回纹」的一小段抽象：横—竖—横，各 12/6/12 dp。
 * <p>
 * 用法：
 *   <net.xuanzhi.launcher.ui.window.LatticeFrame
 *       android:layout_width="match_parent" android:layout_height="match_parent"
 *       app:lattice_inset="14dp" app:lattice_color="#8C6B4F" app:lattice_thickness="1.2dp">
 *       &lt;!-- 你的内容，自动内缩一圈留白 --&gt;
 *   </net.xuanzhi.launcher.ui.window.LatticeFrame>
 * <p>
 * 动画友好：onDraw 里只用 Path + Paint，不做 bitmap，alpha 动画 60fps 无压力。
 */
public final class LatticeFrame extends FrameLayout {

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float inset, thick, corner;
    private int color = 0x8C6B4F;   // 熟褐 · 印泥干后的那种
    private float alphaNorm = 1f;

    public LatticeFrame(Context c) { this(c, null); }
    public LatticeFrame(Context c, AttributeSet a) { this(c, a, 0); }
    public LatticeFrame(Context c, AttributeSet a, int def) { super(c, a, def); init(a); }

    private void init(AttributeSet a) {
        if (a != null) {
            TypedArray ta = getContext().obtainStyledAttributes(a, R.styleable.LatticeFrame);
            inset = ta.getDimension(R.styleable.LatticeFrame_lattice_inset, dp(14));
            thick = ta.getDimension(R.styleable.LatticeFrame_lattice_thickness, dp(1.2f));
            corner = ta.getDimension(R.styleable.LatticeFrame_lattice_corner, dp(22));
            color = ta.getColor(R.styleable.LatticeFrame_lattice_color, color);
            ta.recycle();
        }
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.SQUARE);
        ink.setStrokeJoin(Paint.Join.MITER);
        setWillNotDraw(false);
        // 内容内缩：真正的「留白」靠 padding，不是靠背景图
        int p = (int) inset;
        if (getPaddingLeft() < p) setPadding(p, p, p, p);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        build(w, h);
    }

    private void build(int w, int h) {
        path.reset();
        float i = inset, t = thick * 0.5f;
        float c = corner;
        float x1 = i + t, x2 = w - i - t, y1 = i + t, y2 = h - i - t;
        // 四边：只画靠近角的一段 + 中点一枚极淡的「棂心」小方块，留白在中间
        for (boolean horiz : new boolean[]{true, false}) {
            float a1 = horiz ? x1 : y1, a2 = horiz ? x2 : y2;
            float b = horiz ? y1 : x1, b2 = horiz ? y2 : x2;
            float len = c;
            // 左上
            if (horiz) path.moveTo(a1, b); else path.moveTo(b, a1);
            if (horiz) path.lineTo(a1 + len, b); else path.lineTo(b, a1 + len);
            // 右上
            if (horiz) path.moveTo(a2, b); else path.moveTo(b2, a1);
            if (horiz) path.lineTo(a2 - len, b); else path.lineTo(b2, a1 + len);
            // 左下
            if (horiz) path.moveTo(a1, b2); else path.moveTo(b, a2);
            if (horiz) path.lineTo(a1 + len, b2); else path.lineTo(b, a2 - len);
            // 右下
            if (horiz) path.moveTo(a2, b2); else path.moveTo(b2, a2);
            if (horiz) path.lineTo(a2 - len, b2); else path.lineTo(b2, a2 - len);
            // 中点棂心：一枚 4dp 方，像窗纸上贴的金箔点
            float midA = (a1 + a2) * 0.5f, midB = (b + b2) * 0.5f;
            float k = dp(2);
            if (horiz) path.addRect(midA - k, b - k, midA + k, b + k, Path.Direction.CW);
            else path.addRect(b - k, midA - k, b + k, midA + k, Path.Direction.CW);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ink.setStrokeWidth(thick);
        ink.setColor((((int) (0xFF * alphaNorm) << 24) | (color & 0x00FFFFFF)));
        canvas.drawPath(path, ink);
        super.onDraw(canvas);
    }

    /** 进场：边框从 0 墨晕到全深，配合 InkInterpolator 就是「卷轴展开后窗棂渐显」 */
    public void appear() {
        setAlpha(0f);
        animate().alpha(1f).setDuration(420)
                .setInterpolator(net.xuanzhi.launcher.ui.anim.InkInterpolator.get())
                .start();
    }

    public void setInkColor(int c) { color = c; invalidate(); }
    public void setInkAlpha(float a) { alphaNorm = a; invalidate(); }

    private float dp(int v) { return v * getResources().getDisplayMetrics().density; }
}
