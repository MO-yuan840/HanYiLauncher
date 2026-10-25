package net.xuanzhi.launcher.ui;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import net.hanyi.launcher.R;

/**
 * 启动日志控制台。挂到 LaunchViewModel 的 log LiveData 上，
 * 游戏跑起来后 stdout/stderr 实时往这滚——排错全靠它。
 */
public final class ConsoleActivity extends AppCompatActivity {

    private TextView log;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_console);
        log = findViewById(R.id.tv_log);
        setSupportActionBar(findViewById(com.google.android.material.R.id.action_bar));
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.title_console);

        LaunchViewModel vm = new ViewModelProvider(this).get(LaunchViewModel.class);
        vm.init(); // 只拿 log 管道，不重复启动
        vm.log().observe(this, line -> {
            if (line == null) return;
            log.append(line);
            log.append("\n");
            // 自动滚到底
            findViewById(R.id.scroll).post(() -> ((android.widget.ScrollView) findViewById(R.id.scroll)).fullScroll(android.view.View.FOCUS_DOWN));
        });

        findViewById(R.id.btn_clear).setOnClickListener(v -> log.setText(""));
    
    /** 「Minecraft 出了些岔子…」—— 故意不放「截图」按钮，只给复制与导出 */
    public static void showCrashDialog(@NonNull android.content.Context ctx,
                                      @NonNull String friendly, @NonNull String log) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx, R.style.XuanZhi);
        b.setTitle(R.string.crash_title);
        b.setMessage(friendly + "\n\n" + ctx.getString(R.string.crash_hint));
        b.setPositiveButton(R.string.crash_copy, (d, w) -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", friendly + "\n\n" + log));
            android.widget.Toast.makeText(ctx, R.string.crash_copied, android.widget.Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton(R.string.crash_export, (d, w) -> exportLog(ctx, log));
        b.setNeutralButton(android.R.string.cancel, null);
        b.show();
    }

    private static void exportLog(@NonNull android.content.Context ctx, @NonNull String log) {
        try {
            java.io.File out = new java.io.File(ctx.getExternalFilesDir(null),
                "HanYi_crash_" + new java.text.SimpleDateFormat("yyMMdd_HHmmss").format(new java.util.Date()) + ".log");
            try (java.io.FileWriter w = new java.io.FileWriter(out)) { w.write(log); }
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.crash_exported, out.getAbsolutePath()),
                android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(ctx, R.string.crash_export_fail, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
