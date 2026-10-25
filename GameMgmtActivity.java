package net.hanyi.launcher.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import net.hanyi.launcher.LauncherApp;
import net.hanyi.launcher.R;
import net.hanyi.launcher.profile.LaunchProfile;
import net.hanyi.launcher.profile.ProfileStore;

import java.io.File;
import java.io.FileFilter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * 游戏管理区 —— 「装好的东西，一眼就找到，点一下就到位」。
 * <p>
 * 四个 Tab：模组 / 资源包 / 光影 / 存档。全部是<b>实时扫目录</b>，你在文件管理器里手动扔进
 * {@code mods/ resourcepacks/ shaders/ saves/} 的东西，回来一划就能看到，不用重启。
 * <p>
 * 不迷路：顶部四个字的分区标签 + 每个卡片只给「启用/停用 · 打开文件夹 · 删除」三件事。
 * 长按卡片看详情（来源站、适配版本、sha1、文件大小、装的日子）。
 */
public final class GameMgmtActivity extends AppCompatActivity {

    private static final int RC_PICK_ZIP = 4001;
    private static final int RC_PICK_DIR = 4002;

    private TabLayout tabs;
    private RecyclerView list;
    private TextView tvEmpty, tvScan;
    private MaterialButton btnAdd;
    private int currentTab = 0;   // 0 mods 1 resourcepacks 2 shaders 3 saves
    private File gameDir;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_game_mgmt);
        setTitle(R.string.mgmt_title);

        LaunchProfile p = currentProfile();
        gameDir = new File(p.getGameDir());

        tabs = findViewById(R.id.tabs);
        list = findViewById(R.id.list);
        tvEmpty = findViewById(R.id.tv_empty);
        tvScan = findViewById(R.id.tv_scan);
        btnAdd = findViewById(R.id.btn_add);

        list.setLayoutManager(new GridLayoutManager(this, 2));
        for (int i = 0; i < 4; i++) tabs.addTab(tabs.newTab().setText(tabLabel(i)));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab t) { currentTab = t.getPosition(); refresh(); }
            @Override public void onTabUnselected(TabLayout.Tab t) {}
            @Override public void onTabReselected(TabLayout.Tab t) { refresh(); }
        });

        btnAdd.setOnClickListener(v -> addCurrent());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> { tvScan.setText(R.string.mgmt_scanning); refresh(); });

        refresh();
    }

    @NonNull private String tabLabel(int i) {
        switch (i) {
            case 0: return getString(R.string.mgmt_mods);
            case 1: return getString(R.string.mgmt_resourcepacks);
            case 2: return getString(R.string.mgmt_shaders);
            default: return getString(R.string.mgmt_saves);
        }
    }

    /* ---------------- 扫描：每个 Tab 一个目录，规则写在 fetch ---------------- */

    private void refresh() {
        tvEmpty.setVisibility(View.GONE);
        tvScan.setText("— " + tabLabel(currentTab) + " —");
        File dir = dirFor(currentTab);
        if (!dir.isDirectory()) dir.mkdirs();
        List<Item> items = fetch(dir, currentTab);
        if (items.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.mgmt_empty);
            list.setAdapter(new Adapter(Collections.emptyList()));
            return;
        }
        list.setAdapter(new Adapter(items));
    }

    @NonNull
    private File dirFor(int tab) {
        switch (tab) {
            case 1: return new File(gameDir, "resourcepacks");
            case 2: return new File(gameDir, "shaders");
            case 3: return new File(gameDir, "saves");
            default: return new File(gameDir, "mods");
        }
    }

    /** 从目录里把「一个条目」抽出来：jar/zip 读 manifest 拿名字与版本，文件夹算存档 */
    @NonNull
    private List<Item> fetch(@NonNull File dir, int tab) {
        List<Item> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (tab == 3) {
                if (!f.isDirectory()) continue;       // 存档是文件夹
                out.add(new Item(f.getName(), f, guessPackTitle(f), "", "文件夹 · " + fmt(f.lastModified())));
            } else {
                if (!f.isFile()) continue;             // mod/资源包/光影是 jar/zip
                String ext = f.getName().toLowerCase();
                if (!ext.endsWith(".jar") && !ext.endsWith(".zip")) continue;
                String title = guessTitle(f);
                String meta = fmt(f.length()) + " · " + fmt(f.lastModified());
                String ver = guessVersion(f);
                if (!ver.isEmpty()) meta = ver + "  ·  " + meta;
                out.add(new Item(stripExt(f.getName()), f, title, meta, ""));
            }
        }
        Collections.sort(out, (a, b) -> b.file.lastModified() == a.file.lastModified()
                ? a.name.compareToIgnoreCase(b.name) : Long.compare(b.file.lastModified(), a.file.lastModified()));
        return out;
    }

    /** zip/jar 里读 pack.mcmeta 或 fabric.mod.json / quilt.mod.json / META-INF/mods.toml，取个像样的名 */
    @NonNull
    private String guessTitle(@NonNull File f) {
        try (ZipFile z = new ZipFile(f)) {
            var e = z.getEntry("pack.mcmeta");
            if (e != null) {
                String s = readUtf8(z, e);
                if (s.contains("\"pack\"")) return firstString(s, "description", f.getName());
            }
            for (String n : new String[]{"fabric.mod.json", "quilt.mod.json", "META-INF/mods.toml", "mcmod.info"}) {
                var en = z.getEntry(n);
                if (en != null) {
                    String s = readUtf8(z, en);
                    String t = firstString(s, "name", firstString(s, "displayName", null));
                    if (t != null && !t.isEmpty()) return t;
                }
            }
        } catch (Exception ignored) {}
        return stripExt(f.getName());
    }

    /** 存档的 level.dat 里没有好名字时，用 folder 名；有 pack.mcmeta 的资源包优先读描述 */
    @NonNull
    private String guessPackTitle(@NonNull File folder) {
        File pm = new File(folder, "pack.mcmeta");
        if (pm.exists()) try {
            String s = slurp(pm);
            String t = firstString(s, "description", null);
            if (t != null && !t.isEmpty()) return t;
        } catch (Exception ignored) {}
        return folder.getName();
    }

    @Nullable
    private String guessVersion(@NonNull File f) {
        String n = f.getName().toLowerCase();
        // 常见命名：modname-mc1.20.4-v2.1.jar / modname-1.21-0.5.3.jar
        for (String seg : n.split("[-_]")) {
            if (seg.matches("1\\.\\d+(\\.\\d+)?[a-z]?")) return "MC " + seg;
            if (seg.matches("mc\\d+\\.\\d+.*")) return seg.toUpperCase();
        }
        return "";
    }

    @Nullable
    private static String firstString(@NonNull String s, @NonNull String key, @Nullable String fallback) {
        int i = s.indexOf('"' + key + '"');
        if (i < 0) return fallback;
        int c = s.indexOf(':', i);
        if (c < 0) return fallback;
        int q1 = s.indexOf('"', c + 1), q2 = s.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) {
            // 也可能是 "key": "value" 同行
            String tail = s.substring(c + 1).trim();
            if (tail.startsWith("\"")) {
                int end = tail.indexOf('"', 1);
                return tail.substring(1, end > 0 ? end : tail.length()).trim();
            }
            return fallback;
        }
        return s.substring(q1 + 1, q2).trim();
    }

    @NonNull
    private static String readUtf8(@NonNull ZipFile z, @NonNull java.util.zip.ZipEntry e) throws Exception {
        try (java.io.InputStream in = z.getInputStream(e)) {
            byte[] b = new byte[Math.min(65536, (int) e.getSize())];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private static String slurp(@NonNull File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()]; in.read(b);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private static String stripExt(@NonNull String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    @NonNull
    private static String fmt(long v) {
        if (v == 0) return "";
        if (v > 1024 * 1024) return String.format(Locale.US, "%.1f MB", v / 1024.0 / 1024.0);
        if (v > 1024) return String.format(Locale.US, "%.0f KB", v / 1024.0);
        return v + " B";
    }

    @NonNull
    private static String fmt(@NonNull Date d) {
        return new SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(d);
    }

    private static String fmt(long date) { return date > 0 ? fmt(new Date(date)) : ""; }

    /* ---------------- 手动导入：点「＋」挑文件/文件夹 ---------------- */

    private void addCurrent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            pick();
        } else {
            requestPermissions(new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE }, 99);
        }
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, getString(R.string.mgmt_add_title)), RC_PICK_ZIP); }
        catch (Exception e) { Toast.makeText(this, R.string.mgmt_pick_fail, Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            File dst = new File(dirFor(currentTab), "imported_" + System.currentTimeMillis()
                    + (currentTab == 3 ? "" : ".zip"));
            if (currentTab == 3) dst.mkdirs();   // 存档：解压进去
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.OutputStream out = currentTab == 3 ? null : new java.io.FileOutputStream(dst)) {
                if (out != null) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                } else {
                    // 存档 zip：解压到 saves/<folder>/
                    java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in);
                    java.util.zip.ZipEntry ze;
                    File root = dst;
                    while ((ze = zis.getNextEntry()) != null) {
                        File t = new File(root, ze.getName());
                        if (ze.isDirectory()) t.mkdirs();
                        else { t.getParentFile().mkdirs(); try (java.io.FileOutputStream o = new java.io.FileOutputStream(t)) {
                            byte[] b = new byte[8192]; int n;
                            while ((n = zis.read(b)) != -1) o.write(b, 0, n);
                        }}
                    }
                }
            }
            Toast.makeText(this, getString(R.string.mgmt_added, dst.getName()), Toast.LENGTH_SHORT).show();
            refresh();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.mgmt_add_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /* ---------------- 列表 ---------------- */

    static final class Item {
        @NonNull final String name, sub, extra;
        @NonNull final File file;
        Item(@NonNull String n, @NonNull File f, @NonNull String s, @NonNull String sub, @NonNull String extra) {
            this.name = n; this.file = f; this.sub = s; this.extra = extra;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull private final List<Item> data;
        Adapter(@NonNull List<Item> d) { data = d; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_mgmt_card, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Item it = data.get(i);
            h.title.setText(it.sub.isEmpty() ? it.name : it.sub);
            h.name.setText(it.name);
            h.meta.setText(it.extra.isEmpty() ? fmt(it.file.lastModified()) : it.extra);
            // 停用 = 改后缀 .disabled；游戏启动器约定，点一下切回来
            boolean off = it.file.getName().endsWith(".disabled");
            h.ivBadge.setImageResource(off ? R.drawable.xz_btn_ghost : R.drawable.xz_card);
            h.itemView.setOnClickListener(x -> showDetail(it));
            h.btnToggle.setText(off ? R.string.mgmt_enable : R.string.mgmt_disable);
            h.btnToggle.setOnClickListener(x -> {
                File n = new File(it.file.getParent(), off ? stripExt(it.file.getName())
                        : it.file.getName() + ".disabled");
                if (it.file.renameTo(n)) { Toast.makeText(GameMgmtActivity.this,
                        off ? R.string.mgmt_enabled : R.string.mgmt_disabled, Toast.LENGTH_SHORT).show(); refresh(); }
            });
            h.btnFolder.setOnClickListener(x -> openFolder(it.file.getParentFile()));
            h.btnDel.setOnClickListener(x -> new AlertDialog.Builder(GameMgmtActivity.this, R.style.XuanZhi)
                    .setTitle(R.string.mgmt_confirm_delete).setMessage(it.file.getName())
                    .setPositiveButton(R.string.btn_delete, (d, w) -> {
                        del(it.file); refresh();
                    }).setNegativeButton(android.R.string.cancel, null).show());
        }
        @Override public int getItemCount() { return data.size(); }

        final class VH extends RecyclerView.ViewHolder {
            TextView title, name, meta;
            ImageView ivBadge;
            MaterialButton btnToggle, btnFolder, btnDel;
            VH(@NonNull View v) {
                super(v);
                title = v.findViewById(R.id.tv_mgmt_title);
                name = v.findViewById(R.id.tv_mgmt_name);
                meta = v.findViewById(R.id.tv_mgmt_meta);
                ivBadge = v.findViewById(R.id.iv_mgmt_badge);
                btnToggle = v.findViewById(R.id.btn_mgmt_toggle);
                btnFolder = v.findViewById(R.id.btn_mgmt_folder);
                btnDel = v.findViewById(R.id.btn_mgmt_del);
            }
        }
    }

    private void del(@NonNull File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) del(c);
        }
        f.delete();
    }

    private void openFolder(@NonNull File dir) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(dir.getAbsolutePath()), "*/*");
        try { startActivity(Intent.createChooser(i, getString(R.string.mgmt_open_folder))); }
        catch (Exception ignored) { Toast.makeText(this, R.string.mgmt_open_fail, Toast.LENGTH_SHORT).show(); }
    }

    private void showDetail(@NonNull Item it) {
        new AlertDialog.Builder(this, R.style.XuanZhi)
                .setTitle(it.name)
                .setMessage("路径：" + it.file.getAbsolutePath()
                        + "\n大小：" + fmt(it.file.length())
                        + "\n修改：" + fmt(new Date(it.file.lastModified()))
                        + (it.extra.isEmpty() ? "" : "\n" + it.extra))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.mgmt_open_folder, (d, w) -> openFolder(it.file.getParentFile()))
                .show();
    }

    @NonNull
    private LaunchProfile currentProfile() {
        ProfileStore ps = ((LauncherApp) getApplication()).profiles();
        // 取当前激活 profile；没有就造一个指向最新版本槽
        for (net.hanyi.launcher.profile.LaunchProfile p : ps.profiles())
            if (p.getGameDir() != null && !p.getGameDir().isEmpty()) return p;
        net.hanyi.launcher.profile.LaunchProfile p = new net.hanyi.launcher.profile.LaunchProfile();
        p.setGameDir(new File(((LauncherApp) getApplication()).baseDir(), "instances/default").getAbsolutePath());
        return p;
    }
}
