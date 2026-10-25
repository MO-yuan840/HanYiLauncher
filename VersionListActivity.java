package net.xuanzhi.launcher.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import net.hanyi.launcher.LauncherApp;
import net.hanyi.launcher.R;
import net.hanyi.launcher.meta.VersionManifest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 版本选择器：从 Mojang manifest 拉全量列表，按 release/snapshot/old 分组筛选。
 * 点一个版本 -> 设为当前 profile.versionId -> 回主界面直接启动。
 */
public final class VersionListActivity extends AppCompatActivity {

    private final List<VersionManifest.Entry> all = new ArrayList<>();
    private String filter = "release";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_version_list);
        setSupportActionBar(findViewById(com.google.android.material.R.id.action_bar));
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.title_versions);

        RecyclerView rv = findViewById(R.id.rv_versions);
        VersionAdapter ad = new VersionAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(ad);

        ChipGroup cg = findViewById(R.id.chip_group);
        cg.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.chip_release) filter = "release";
            else if (id == R.id.chip_snapshot) filter = "snapshot";
            else filter = "all";
            ad.notifyDataSetChanged();
        });

        findViewById(R.id.btn_refresh).setOnClickListener(v -> load(ad));

        load(ad);
    }

    private void load(VersionAdapter ad) {
        setStatus("拉取版本清单…");
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                VersionManifest m = new net.xuanzhi.launcher.meta.MetaRepository(
                        LauncherApp.get().http(), LauncherApp.get().om(), LauncherApp.get().baseDir()).manifest();
                all.clear();
                all.addAll(m.versions);
                runOnUiThread(() -> { ad.notifyDataSetChanged(); setStatus("共 " + all.size() + " 个版本"); });
            } catch (IOException e) {
                runOnUiThread(() -> setStatus("拉取失败：" + e.getMessage()));
            }
        });
    }

    private void setStatus(String s) { ((TextView) findViewById(R.id.tv_status)).setText(s); }

    private class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.VH> {
        @Override public int getItemCount() {
            if (filter.equals("all")) return all.size();
            String t = filter.equals("snapshot") ? "snapshot" : "release";
            int c = 0; for (VersionManifest.Entry e : all) if (t.equals(e.type)) c++;
            return c;
        }
        private VersionManifest.Entry get(int p) {
            if (filter.equals("all")) return all.get(p);
            String t = filter.equals("snapshot") ? "snapshot" : "release";
            int i = 0; for (VersionManifest.Entry e : all) { if (t.equals(e.type)) { if (i == p) return e; i++; } }
            return all.get(p);
        }
        @Override public VH onCreateViewHolder(ViewGroup parent, int t) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_version, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int p) {
            VersionManifest.Entry e = get(p);
            h.name.setText(e.display());
            h.type.setText(e.type);
            h.itemView.setOnClickListener(v -> {
                net.xuanzhi.launcher.profile.LaunchProfile pr = new net.xuanzhi.launcher.profile.LaunchProfile();
                pr.versionId = e.id;
                // 新建 profile 并激活（简化：直接改“默认”那条）
                pr.id = "default";
                pr.gameDir = net.xuanzhi.launcher.profile.LaunchProfile.defaultGameDir(
                        LauncherApp.get().baseDir().getAbsolutePath(), e.id);
                java.util.List<net.xuanzhi.launcher.profile.LaunchProfile> ps =
                        LauncherApp.get().profiles().profiles();
                ps.removeIf(x -> "default".equals(x.id));
                ps.add(pr);
                LauncherApp.get().profiles().saveProfiles(ps);
                Toast.makeText(VersionListActivity.this, "已选择 " + e.id, Toast.LENGTH_SHORT).show();
                finish();
            });
        }
        class VH extends RecyclerView.ViewHolder {
            TextView name, type;
            VH(View v) { super(v); name = v.findViewById(R.id.v_name); type = v.findViewById(R.id.v_type); }
        }
    }
}
