package net.xuanzhi.launcher.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import net.hanyi.launcher.BuildConfig;
import net.hanyi.launcher.R;
import net.hanyi.launcher.auth.Account;
import net.hanyi.launcher.profile.LaunchProfile;
import net.hanyi.launcher.profile.ProfileStore;

/**
 * 启动器主界面：账号卡 / 版本卡 / 状态 / 启动按钮。
 * 所有重活都在 LaunchViewModel 里，这里只做绑定。
 */
public final class LaunchActivity extends AppCompatActivity {

    private LaunchViewModel vm;
    private TextView tvAccount, tvAccountHint, tvVersion, tvVersionMeta, tvStatus, tvDetail, tvFoot;
    private ProgressBar progress;
    private MaterialButton btnPlay, btnVersions, btnConsole, btnSettings, btnSwitch;
    private MaterialCardView cardAccount, cardVersion;

    private LaunchProfile profile = new LaunchProfile();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_launch);
        setTitle(getString(R.string.app_name));

        vm = new ViewModelProvider(this).get(LaunchViewModel.class);
        bind();
        vm.init();

        btnPlay.setOnClickListener(v -> onPlay());
        btnVersions.setOnClickListener(v -> startActivity(new Intent(this, VersionListActivity.class)));
        btnConsole.setOnClickListener(v -> startActivity(new Intent(this, ConsoleActivity.class)));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_web).setOnClickListener(v -> startActivity(new Intent(this, WebSitesActivity.class)));
        findViewById(R.id.btn_mgmt).setOnClickListener(v -> startActivity(new Intent(this, GameMgmtActivity.class)));
        findViewById(R.id.btn_touch).setOnClickListener(v -> startActivity(new Intent(this, TouchConfigActivity.class)));
        cardAccount.setOnClickListener(v -> startActivityForResult(new Intent(this, AuthActivity.class), 1001));
        btnSwitch.setOnClickListener(v -> startActivityForResult(new Intent(this, AuthActivity.class), 1001));
        cardVersion.setOnClickListener(v -> startActivity(new Intent(this, VersionListActivity.class)));
    }

    private void bind() {
        tvAccount = findViewById(R.id.tv_account);
        tvAccountHint = findViewById(R.id.tv_account_hint);
        tvVersion = findViewById(R.id.tv_version);
        tvVersionMeta = findViewById(R.id.tv_version_meta);
        tvStatus = findViewById(R.id.tv_status);
        tvDetail = findViewById(R.id.tv_detail);
        tvFoot = findViewById(R.id.tv_foot);
        progress = findViewById(R.id.progress);
        btnPlay = findViewById(R.id.btn_play);
        btnVersions = findViewById(R.id.btn_versions);
        btnConsole = findViewById(R.id.btn_console);
        btnSettings = findViewById(R.id.btn_settings);
        btnSwitch = findViewById(R.id.btn_switch_account);
        cardAccount = findViewById(R.id.card_account);
        cardVersion = findViewById(R.id.card_version);

        vm.state().observe(this, s -> {
            if (s == null) return;
            tvStatus.setText(s.status);
            tvDetail.setText(s.detail);
            progress.setIndeterminate(s.progressIndeterminate);
            if (!s.progressIndeterminate && s.progressTotal > 0) {
                progress.setMax(100);
                progress.setProgress((int) (100L * s.progressDone / Math.max(1, s.progressTotal)));
            }
            boolean running = s.phase == LaunchViewModel.Phase.RUNNING;
            btnPlay.setText(running ? "停止" : getString(R.string.btn_play));
            btnPlay.setBackgroundTintList(
                getResources().getColorStateList(running ? android.R.color.holo_red_dark : R.color.design_default_color_primary));
        });

        // 控制台日志流到 UI（可选：只在 ConsoleActivity 里挂更省）
        vm.log().observe(this, line -> { /* no-op here */ });
    }

    private void onPlay() {
        LaunchViewModel.Phase p = vm.state().getValue() == null ? null : vm.state().getValue().phase;
        if (p == LaunchViewModel.Phase.RUNNING) { vm.stop(); return; }

        ProfileStore store = ((net.xuanzhi.launcher.LauncherApp) getApplication()).profiles();
        Account account = store.active();
        if (account == null) {
            // 离线兜底：给个随机 uuid 的离线账号，用户也能直接玩单机
            account = ensureOffline(store);
        }
        if (account.isOnline() && account.isExpired()) {
            Toast.makeText(this, "微软令牌已过期，请重新登录", Toast.LENGTH_SHORT).show();
            startActivityForResult(new Intent(this, AuthActivity.class), 1001);
            return;
        }

        // profile 还没选版本时，自动用最新 release
        if (profile.versionId.isEmpty()) {
            tvVersion.setText("最新正式版（自动）");
        }
        profile.gameDir = LaunchProfile.defaultGameDir(
                ((net.xuanzhi.launcher.LauncherApp) getApplication()).baseDir().getAbsolutePath(),
                profile.versionId.isEmpty() ? "latest" : profile.versionId);

        vm.launch(profile, account);
    }

    private Account ensureOffline(ProfileStore store) {
        Account a = new Account.OfflineAccount();
        a.localId = "offline-default";
        a.displayName = "Steve-手机";
        a.uuid = java.util.UUID.randomUUID().toString();
        store.upsert(a);
        store.setActive(a);
        refreshAccount();
        return a;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccount();
    }

    private void refreshAccount() {
        Account a = ((net.xuanzhi.launcher.LauncherApp) getApplication()).profiles().active();
        if (a == null) {
            tvAccount.setText(R.string.label_no_account);
            tvAccountHint.setText("点这里用微软正版账号登录");
        } else {
            tvAccount.setText(a.displayName + (a.isOnline() ? "  ✓ 正版" : "  (离线)"));
            tvAccountHint.setText(a.isOnline() ? "微软账号 · 点此切换" : "离线模式 · 进正版服会被拒");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1001) refreshAccount();
    }
}
