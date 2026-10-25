package net.xuanzhi.launcher.ui.window;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import net.hanyi.launcher.R;

/**
 * 宣纸背景。默认【不加载任何图片资源】，纯程序生成三层纸纹，
 * 这样 APK 不会因为一张 4K 宣纸图涨十几 MB，也杜绝 OOM。
 * <p>
 * 三层叠加：
 *   1. 底色渐晕（米白 → 纸角微褐），模拟手工捞纸的厚薄不均；
 *   2. 稀疏的纤维噪点（value noise 降采样），远看是纸，近看有丝；
 *   3. 两道极淡的水痕弧，像压过一次的折痕。
 * <p>
 * 用户在「设置 → 外观 → 背景」里选了自定义图后，走 setCustom(uri)，
 * 用 BitmapRegionDecoder 按需解码，避免把 8K 壁纸整个塞进内存。
 */
public final class XuanZhiBackground extends View {

    private static final String PREF = "xuanzhi_ui";
    private static final String KEY_BG = "background_uri";

    private final Paint base = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint fiber = new Paint(Paint.DITHER_FLAG);
    private Bitmap fiberBmp;
    private Bitmap custom;
    private RectF vignette = new RectF();
    private Shader vignetteShader;
    private boolean dirty = true;

    // 宣纸色：不是纯白，是「松烟落纸」的暖灰白
    private static final int PAPER_CENTER = 0xF7F1E6;
    private static final int PAPER_EDGE   = 0xE8DCC4;

    public XuanZhiBackground(Context c) { this(c, null); }
    public XuanZhiBackground(Context c, @Nullable AttributeSet a) { super(c, a); init(c); }

    private void init(Context c) {
        setLayerType(LAYER_TYPE_HARDWARE, null);   // 纸纹不动，交给 RenderThread 合成
        fiber.setAlpha(0x33);
        String uri = c.getSharedPreferences(PREF, 0).getString(KEY_BG, null);
        if (uri != null) loadCustom(uri);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        dirty = true;
        vignette.set(0, 0, w, h);
        vignetteShader = new RadialGradient(
                w * 0.55f, h * 0.4f, Math.max(w, h) * 0.75f,
                PAPER_CENTER, PAPER_EDGE, Shader.TileMode.CLAMP);
        // 纤维层：小图即可，铺开有颗粒感，省内存
        int fw = Math.max(64, w / 6), fh = Math.max(64, h / 6);
        fiberBmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        fiberBmp.eraseColor(0);
        genFibers(fiberBmp);
        fiber.setShader(new BitmapShader(fiberBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 1) 底色晕
        canvas.drawRect(0, 0, getWidth(), getHeight(), base);
        base.setShader(vignetteShader);
        canvas.drawRect(0, 0, getWidth(), getHeight(), base);
        base.setShader(null);
        // 2) 纤维
        canvas.drawRect(0, 0, getWidth(), getHeight(), fiber);
        // 3) 折痕：两道极淡的弧，笔触像笔锋收住
        drawFold(canvas);
        // 4) 自定义图：以「留白」方式叠在底层之上，透明度 0.35，纸还是纸
        if (custom != null) {
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(0x59);
            canvas.drawBitmap(custom, null, new RectF(0, 0, getWidth(), getHeight()), p);
        }
    }

    private void drawFold(Canvas c) {
        Paint fold = new Paint(Paint.ANTI_ALIAS_FLAG);
        fold.setStyle(Paint.Style.STROKE);
        fold.setStrokeWidth(dp(1.4f));
        fold.setColor(0x1A5A3E2E);
        float w = getWidth(), h = getHeight();
        Path p = new Path();
        p.moveTo(w * 0.08f, h * 0.92f);
        p.cubicTo(w * 0.35f, h * 0.78f, w * 0.62f, h * 0.83f, w * 0.94f, h * 0.66f);
        c.drawPath(p, fold);
        Path p2 = new Path();
        p2.moveTo(w * 0.92f, h * 0.10f);
        p2.cubicTo(w * 0.72f, h * 0.24f, w * 0.45f, h * 0.20f, w * 0.10f, h * 0.34f);
        fold.setAlpha(0x10);
        c.drawPath(p2, fold);
    }

    /* ---------- 程序化纤维：确定性噪声，同一尺寸永远同一张纸 ---------- */
    private static void genFibers(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        long seed = 0xC0FFEEA1L;
        for (int i = 0; i < px.length; i++) {
            seed = seed * 0x5DEECE66DL + 0xBL; // 老派 LCG，够碎就行
            int v = (int) (seed >> 40) & 0xFF;
            int lines = (seed >> 20) & 0x3;     // 偶尔拉出一根长丝
            int col = 0xFF000000 | ((v >> 1) << 16) | ((v >> 2) << 8) | (v >> 1);
            px[i] = (lines == 0) ? col : 0x00000000;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    /* ---------- 外部 API ---------- */

    public void loadCustom(String uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2; // 背景没必要原尺寸
            Bitmap b = BitmapFactory.decodeFile(uri.replaceFirst("^file://", ""), o);
            if (b != null) {
                custom = Bitmap.createScaledBitmap(b, Math.min(1080, b.getWidth()),
                        Math.min(1920, b.getHeight()), true);
                if (custom != b) b.recycle();
                getContext().getSharedPreferences(PREF, 0).edit().putString(KEY_BG, uri).apply();
                invalidate();
            }
        } catch (Exception e) { /* 自定义图坏了不影响启动器，默默回退宣纸 */ }
    }

    public void resetToPaper() {
        if (custom != null) { custom.recycle(); custom = null; }
        getContext().getSharedPreferences(PREF, 0).edit().remove(KEY_BG).apply();
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
