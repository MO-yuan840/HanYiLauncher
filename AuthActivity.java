package net.xuanzhi.launcher.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import net.hanyi.launcher.R;
import net.hanyi.launcher.auth.Account;
import net.hanyi.launcher.auth.MicrosoftAuth;

import java.util.concurrent.Executors;

/**
 * 登录页：
 *  - 微软：WebView 走 PKCE，拦截回调 URL 抠 code，后台串完 XBL/XSTS/Minecraft
 *  - 离线：输个名字就完事
 *
 * 注意：若 AAD_CLIENT_ID 没填，微软登录按钮会直接提示，不会静默失败。
 */
public final class AuthActivity extends AppCompatActivity {

    private WebView web;
    private String verifier;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_auth);

        web = findViewById(R.id.web);
        EditText nameInput = findViewById(R.id.et_offline_name);
        MaterialButton btnOffline = findViewById(R.id.btn_offline_confirm);
        MaterialButton btnMs = findViewById(R.id.btn_ms_login);
        RadioGroup mode = findViewById(R.id.mode_group);

        // 默认显示离线区，点微软再切到 WebView
        mode.setOnCheckedChangeListener((g, id) -> {
            boolean ms = id == R.id.mode_ms;
            web.setVisibility(ms ? android.view.View.VISIBLE : android.view.View.GONE);
            nameInput.setVisibility(ms ? android.view.View.GONE : android.view.View.VISIBLE);
            btnOffline.setVisibility(ms ? android.view.View.GONE : android.view.View.VISIBLE);
        });

        btnOffline.setOnClickListener(v -> {
            String n = nameInput.getText().toString().trim();
            if (n.isEmpty()) { nameInput.setError("起个名字吧"); return; }
            Account.OfflineAccount a = new Account.OfflineAccount();
            a.localId = "offline-" + n;
            a.displayName = n;
            a.uuid = java.util.UUID.randomUUID().toString();
            storeAndFinish(a);
        });

        btnMs.setOnClickListener(v -> startMicrosoftFlow());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().removeAllCookies(null);
    }

    private void startMicrosoftFlow() {
        if (MicrosoftAuth.AAD_CLIENT_ID.isEmpty()) {
            Toast.makeText(this, "启动器还没配置 Azure client_id，先用离线模式，或看 MicrosoftAuth.java 顶部注释",
                    Toast.LENGTH_LONG).show();
            return;
        }
        verifier = MicrosoftAuth.newVerifier();
        try {
            String url = MicrosoftAuth.authorizeUrl(verifier);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    String code = MicrosoftAuth.peelCode(url);
                    if (code != null) {
                        web.setVisibility(android.view.View.GONE);
                        redeem(code);
                    }
                }
            });
            web.loadUrl(url);
        } catch (IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 在子线程里把 code 一路换成 Minecraft profile，成功就存下来。 */
    private void redeem(@NonNull String code) {
        Toast.makeText(this, "正在验证微软账号（XBL → Minecraft）…", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MicrosoftAuth.MicrosoftAccount acc = MicrosoftAuth.fullFlow(code, verifier);
                runOnUiThread(() -> storeAndFinish(acc));
            } catch (MicrosoftAuth.HasNoLicense e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.err_owns_mc, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "登录失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void storeAndFinish(@NonNull Account a) {
        ((net.xuanzhi.launcher.LauncherApp) getApplication()).profiles().upsert(a);
        ((net.xuanzhi.launcher.LauncherApp) getApplication()).profiles().setActive(a);
        Toast.makeText(this, "已登录：" + a.displayName, Toast.LENGTH_SHORT).show();
        setResult(Activity.RESULT_OK);
        finish();
    }
}
