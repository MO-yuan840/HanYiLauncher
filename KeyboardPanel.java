package net.xuanzhi.launcher.controls;

import android.app.Dialog;
import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import net.hanyi.launcher.R;

/** 软键盘面板：点「控」字弹出，输入走 InputBridge.typeText 喂给游戏。
 *  用 Dialog + TYPE_APPLICATION_ATTACHED_DIALOG，能盖在游戏窗口之上又不抢焦点。 */
public final class KeyboardPanel {
    public static void show(Context ctx) {
        Dialog d = new Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        EditText box = new EditText(ctx);
        box.setBackgroundResource(R.drawable.xz_btn_ghost);
        box.setTextColor(0xFF2B2118); box.setHintTextColor(0x885C4A38);
        box.setHint("在此落字，回车即送入游戏");
        box.setPadding(36, 24, 36, 24);
        box.setOnEditorActionListener((v, act, ev) -> {
            InputBridge.get().typeText(v.getText().toString());
            v.setText(""); return true;
        });
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                         WindowManager.LayoutParams.WRAP_CONTENT);
            w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                       WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        }
        d.show();
        box.requestFocus();
    }
}
