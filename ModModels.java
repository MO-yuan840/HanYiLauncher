package net.xuanzhi.launcher.mods;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * 两个源（Modrinth / CurseForge）的统一数据模型。
 * 关键点：DepEdge 同时带 projectId(base62) 与 cfModId(long)，
 * 解析时先按同源性查本地已装索引，找不到再反查另一源 —— 这样不会出
 * 「Modrinth 上写依赖某个 CF 模组」就直接装漏的尴尬。
 */
public final class ModModels {

    private ModModels() {}

    /** 搜索结果卡片用，轻量 */
    public static final class ModStub {
        public String source = "";      // "modrinth" | "curseforge"
        public String projectId = "";   // modrinth base62 / cf 的 id 字符串
        public String slug = "";
        public String title = "";
        public String summary = "";
        public String author = "";
        public String iconUrl = "";
        public long downloads = 0;
        public String dateCreated = "";
        public final List<String> categories = new ArrayList<>();
        public final List<String> gameVersions = new ArrayList<>();

        public boolean matches(String mc, String loader) {
            if (mc != null && !gameVersions.isEmpty()
                    && !gameVersions.stream().anyMatch(v -> v.startsWith(mc)))
                return false;
            if (loader != null && !categories.isEmpty()
                    && !categories.stream().anyMatch(c -> c.equalsIgnoreCase(loader)))
                return false;
            return true;
        }
    }

    /** 一个具体可下载的版本 */
    public static final class ModVersion {
        public String id = "";
        public String projectId = "";
        public String versionNumber = "";
        public String name = "";
        public final List<String> gameVersions = new ArrayList<>();
        public final List<String> loaders = new ArrayList<>();
        public String datePublished = "";
        public final List<ModFile> files = new ArrayList<>();
        public final List<DepEdge> dependencies = new ArrayList<>();
        // CurseForge 专用
        public long cfModId = 0, cfFileId = 0;
        public String cfDownloadUrl = null;

        public ModFile primaryFile() {
            for (ModFile f : files) if (f.primary) return f;
            return files.isEmpty() ? new ModFile() : files.get(0);
        }

        public boolean supports(String mc, String loader) {
            boolean mcOk = mc == null || gameVersions.stream().anyMatch(v -> v.startsWith(mc));
            boolean ldOk = loader == null || loaders.stream().anyMatch(l -> l.equalsIgnoreCase(loader));
            return mcOk && ldOk;
        }
    }

    public static final class ModFile {
        public String url = "";
        public String filename = "";
        public long size = -1;
        public boolean primary = false;
        public String sha1 = "";
    }

    /** 依赖边。required 为 true 才需要自动装 */
    public static final class DepEdge {
        public String versionId = "";
        public String projectId = "";   // modrinth
        public long cfModId = 0;        // curseforge
        public long cfFileId = 0;
        public String fileName = "";     // 空=由解析器自动挑兼容版
        public String type = "required";
        public boolean required = true;

        /** 同一条边能否指到已装的另一条边（双源去重用） */
        public boolean pointsTo(@NonNull DepEdge o) {
            if (!projectId.isEmpty() && projectId.equals(o.projectId)) return true;
            if (cfModId != 0 && cfModId == o.cfModId) return true;
            return false;
        }

        @NonNull
        @Override
        public String toString() {
            return "dep:" + (projectId.isEmpty() ? ("cf:" + cfModId) : projectId)
                    + (required ? "!" : "?");
        }
    }

    /** 依赖解析结果：nodes 已按「被依赖在前」排好，直接顺序下载即可 */
    public static final class DepGraph {
        public final ModVersion root;
        public final List<ModVersion> nodes = new ArrayList<>();
        public final List<DepEdge> unresolved = new ArrayList<>();
        public int depth = 0;

        DepGraph(@NonNull ModVersion root) { this.root = root; nodes.add(root); }

        public List<ModVersion> installOrder() {
            // root 在末尾：前置先装
            List<ModVersion> out = new ArrayList<>(nodes);
            ModVersion r = out.remove(out.indexOf(root));
            out.add(r);
            return out;
        }

        public boolean hasMissing() { return !unresolved.isEmpty(); }
    }
}
