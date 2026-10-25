package net.xuanzhi.launcher.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.*;
import net.hanyi.launcher.mods.*;
import net.hanyi.launcher.profile.LaunchProfile;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模组浏览/安装 的 ViewModel。
 * <p>
 * 「搜不到」的三重保险：
 *   ① Modrinth（免 Key）+ CurseForge（有 Key 才上）并发飞，任一源慢/挂都不拖另一条；
 *   ② 两边结果按 normalize(title) 去重，同模组只显一条、徽标标出两个源都在；
 *   ③ 网络全灭 → 退化为本地索引模糊匹配，至少不给你空列表。
 * <p>
 * 「前置自动装」：点安装后先 resolveDeps 展开整棵树，按依赖顺序依次下，
 * 已装同版本的 jar 直接跳过。进度是「全局百分比」，UI 上就是那条墨痕。
 */
public final class ModBrowserViewModel extends AndroidViewModel {

    private final ModRepository repo;
    private final LoaderInstaller loader;

    private final MutableLiveData<List<ModModels.ModStub>> results = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<String> status = new MutableLiveData<>("");
    private final MutableLiveData<Integer> progress = new MutableLiveData<>(-1); // -1=隐藏
    private final MutableLiveData<InstallPlan> plan = new MutableLiveData<>();

    /** 当前筛选：null 表示不限 */
    private String mc = null, loaderName = null;

    public ModBrowserViewModel(@NonNull Application app) {
        super(app);
        repo = new ModRepository(app);
        loader = new LoaderInstaller(app);
    }

    /* ---------------- 输出 ---------------- */

    public LiveData<List<ModModels.ModStub>> getResults() { return results; }
    public LiveData<Boolean> isLoading() { return loading; }
    public LiveData<String> getStatus() { return status; }
    public LiveData<Integer> getProgress() { return progress; }
    public LiveData<InstallPlan> getPlan() { return plan; }

    /** 已装模组清单（mods/ 目录扫描，带版本嗅探） */
    public List<InstalledMod> listInstalled(@NonNull LaunchProfile profile) {
        File dir = profile.getModsDir();
        File[] fs = dir.listFiles(f -> f.getName().endsWith(".jar"));
        List<InstalledMod> out = new ArrayList<>();
        if (fs == null) return out;
        for (File f : fs) {
            InstalledMod m = new InstalledMod();
            m.file = f;
            m.name = f.getName().replaceAll("\\.jar$", "");
            // 试图从文件名里抠出版本：最后一段带数字的部分
            int i = m.name.lastIndexOf('-');
            if (i > 0 && m.name.substring(i + 1).matches(".*\\d.*")) {
                m.version = m.name.substring(i + 1);
                m.name = m.name.substring(0, i);
            }
            out.add(m);
        }
        out.sort(Comparator.comparing(a -> a.name));
        return out;
    }

    /* ---------------- 搜索 ---------------- */

    public void search(@Nullable String q) {
        loading.setValue(true);
        status.setValue("墨海寻珍…");
        repo.search(q, mc, loaderName)
                .thenAccept(list -> {
                    List<ModModels.ModStub> safe = list == null ? new ArrayList<>() : list;
                    results.postValue(safe);
                    loading.postValue(false);
                    status.postValue(safe.isEmpty() ? "未得；已改查本地旧识" : "");
                })
                .exceptionally(t -> { loading.postValue(false);
                    status.postValue("双源皆不通，已切本地索引"); return null; });
    }

    public void setFilter(@Nullable String mc, @Nullable String loader) {
        this.mc = mc; this.loaderName = loader;
    }

    /* ---------------- 加载器：装模组前必须就位 ---------------- */

    public void ensureLoader(@NonNull LaunchProfile profile, @NonNull LoaderInstaller.Kind kind,
                            @NonNull String version, @NonNull Runnable onReady) {
        if (!loaderNameEquiv(profile.getLoader(), kind)) {
            installLoader(kind, version, profile, onReady);
        } else {
            onReady.run();
        }
    }

    private void installLoader(@NonNull LoaderInstaller.Kind kind, @NonNull String ver,
                              @NonNull LaunchProfile profile, @NonNull Runnable done) {
        progress.postValue(0);
        status.postValue(getApplication().getString(net.xuanzhi.launcher.R.string.loader_installing));
        new Thread(() -> {
            try {
                loader.install(kind, profile, ver, new net.xuanzhi.launcher.mods.LoaderInstaller.ProgressSink() {
                    @Override public void on(int p) {
                        progress.postValue(p);
                        if (p >= 100) {
                            progress.postValue(-1);
                            status.postValue(getApplication().getString(net.xuanzhi.launcher.R.string.loader_ready));
                            done.run();
                        }
                    }
                });
            } catch (Exception e) {
                progress.postValue(-1);
                status.postValue("加载器装不上：" + e.getMessage());
            }
        }, "xuanzhi-loader").start();
    }

    private static boolean loaderNameEquiv(@NonNull String have, @NonNull LoaderInstaller.Kind want) {
        return have.equalsIgnoreCase(want.name());
    }

    /* ---------------- 安装：先算依赖树 ---------------- */

    public void install(@NonNull ModModels.ModStub stub, @NonNull LaunchProfile profile) {
        progress.setValue(0);
        status.setValue("正在观其脉络（解析前置）…");
        new Thread(() -> {
            try {
                ModModels.DepGraph g = repo.resolve(stub.source, stub.projectId, null, profile);
                List<ModModels.ModVersion> order = g.installOrder();
                if (g.hasMissing()) {
                    plan.postValue(new InstallPlan(g));
                    status.postValue(getApplication().getString(
                            net.xuanzhi.launcher.R.string.mod_deps_missing, g.unresolved.size()));
                    // 即便有未知前置也继续装能装的，不卡死
                } else {
                    status.postValue(getApplication().getString(net.xuanzhi.launcher.R.string.mod_deps_ok));
                }
                int total = order.size();
                for (int i = 0; i < total; i++) {
                    final int step = i;
                    ModModels.ModVersion v = order.get(i);
                    if (isInstalled(v, profile)) { tick(step, total); continue; }
                    try { repo.installOneSafe(v, profile, p -> progress.postValue((step * 100 + p) / total));
                } catch (Exception ex) { status.postValue("跳过 "+v.versionNumber+": "+ex.getMessage()); }
                    tick(step, total);
                }
                progress.postValue(-1);
                if (total == 1) status.postValue("已落笔：" + order.get(0).versionNumber);
                else status.postValue("共装 " + total + " 件，前置已在先");
                // 写进本地索引，下次离线也能搜到
                repo.cache(stub);
            } catch (Exception e) {
                progress.postValue(-1);
                status.postValue("安装折了：" + e.getMessage());
            }
        }, "xuanzhi-mod-install").start();
    }

    private void tick(int step, int total) {
        progress.postValue((step + 1) * 100 / Math.max(1, total));
    }

    private boolean isInstalled(@NonNull ModModels.ModVersion v, @NonNull LaunchProfile p) {
        return new File(p.getModsDir(), v.primaryFile().filename).exists();
    }

    /* ---------------- 小数据结构 ---------------- */

    public static final class InstalledMod {
        public File file;
        public String name = "";
        public String version = "";
        public boolean markedForRemoval = false;
        public String display() { return version.isEmpty() ? name : name + "  ·  " + version; }
    }

    public static final class InstallPlan {
        public final ModModels.DepGraph graph;
        public InstallPlan(@NonNull ModModels.DepGraph g) { graph = g; }
        /** 依赖链的人类可读文本，塞进那个「小窗棂」里展示 */
        public String chain() {
            StringBuilder sb = new StringBuilder("▎依赖之序：");
            for (ModModels.ModVersion v : graph.installOrder())
                sb.append("\n　").append(v.versionNumber.isEmpty() ? v.projectId : v.versionNumber);
            if (!graph.unresolved.isEmpty())
                sb.append("\n　⚠ 未取到 ").append(graph.unresolved.size()).append(" 件，可手动补");
            return sb.toString();
        }
    }

    @Override
    protected void onCleared() {
        repo.close();
    }
}
