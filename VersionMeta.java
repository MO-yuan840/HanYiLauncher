package net.xuanzhi.launcher.meta;

import androidx.annotation.NonNull;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 对应 manifest 里 versions[i].url 指向的那个文件，也就是
 * .minecraft/versions/<id>/<id>.json。
 *
 * 这里只解析「能启动」所必需的部分：
 *   downloads.client   -> client.jar 地址 + sha1
 *   libraries          -> 依赖 jar（含 natives，按 os/arch 规则过滤）
 *   assetIndex         -> 资源索引
 *   mainClass / arguments / minecraftArguments（1.12 之前是后者）
 *   javaVersion        -> 该版本要吃 JDK8 还是 JDK17/21
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionMeta {

    @NonNull public String id = "";
    @NonNull public String inheritsFrom = "";
    public int complianceLevel;
    public JavaVersion javaVersion;
    public Downloads downloads;
    public AssetIndex assetIndex;
    public Logging logging;
    public List<Library> libraries = new ArrayList<>();
    @JsonProperty("mainClass") @NonNull public String mainClass = "net.minecraft.client.main.Main";
    // 1.13+ : 结构化 arguments；1.12- : 单行 minecraftArguments
    public Args arguments;
    @JsonProperty("minecraftArguments") @NonNull public String minecraftArguments = "";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JavaVersion {
        public int majorVersion = 8;
        @JsonProperty("component") public String component = "jre-legacy";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Downloads {
        public Artifact client;
        public Artifact server;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Artifact {
        @NonNull public String url = "";
        @NonNull public String sha1 = "";
        public long size;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssetIndex {
        @NonNull public String id = "";
        @NonNull public String url = "";
        @NonNull public String sha1 = "";
        public long size, totalSize;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Logging {
        public LogConfig client;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LogConfig {
        public String type = "log4j2-xml";
        public Artifact file;
        @JsonProperty("argument") public String argument;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Args {
        public List<JsonNode> game = new ArrayList<>();
        public List<JsonNode> jvm = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Library {
        @NonNull public String name = "";       // groupId:artifactId:version
        public JsonNode natives;
        public JsonNode extract;
        public List<Rule> rules;
        public LibraryDownloads downloads;

        /** 按当前平台（Android/arm64）判定这条 lib 要不要下载。 */
        public boolean applies(@NonNull String abi, @NonNull ObjectMapper om) {
            if (rules == null || rules.isEmpty()) return true;
            boolean allowed = false;
            for (Rule r : rules) {
                boolean match = r.os == null;
                if (!match && r.os.name != null) {
                    String n = r.os.name;
                    match = "linux".equals(n) || "android".equals(n);
                    if (match && "arm64".equals(abi)) {
                        match = (r.os.arch == null || r.os.arch.contains("64"));
                    }
                }
                if ("allow".equals(r.action)) allowed |= match;
                else if ("disallow".equals(r.action)) allowed &= !match;
            }
            return allowed;
        }

        /** natives 非空时返回平台化 jar 名片段，如 "-natives-linux-arm64" */
        @NonNull
        public String nativesSuffix(@NonNull String abi) {
            if (natives == null) return "";
            JsonNode v = natives.get(abi);
            return v == null ? "" : v.asText();
        }

        /** 从 name 算出 maven 路径：org.lwjgl:lwjgl:3.x -> org/lwjgl/lwjgl/3.x/lwjgl-3.x.jar */
        @NonNull
        public String mavenPath(@NonNull String suffix) {
            String[] p = name.split(":");
            if (p.length < 3) return name;
            String g = p[0].replace('.', '/'), a = p[1], v = p[2];
            return g + "/" + a + "/" + v + "/" + a + "-" + v + suffix + ".jar";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rule {
        @NonNull public String action = "allow";
        public Os os;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Os {
        public String name;
        public String version;
        public List<String> arch;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LibraryDownloads {
        public Artifact artifact;
        @JsonProperty("classifiers") public JsonNode classifiers;
    }

    /** 1.12 及更早版本用的是字符串式参数，这里统一成 List 供 LaunchConfig 拼装。 */
    @NonNull
    public List<String> legacyArgs(@NonNull String user, @NonNull String uuid, @NonNull String token, @NonNull String assetsDir) {
        String[] tok = minecraftArguments.split(" ");
        List<String> out = new ArrayList<>();
        for (String t : tok) {
            out.add(t
                .replace("${auth_player_name}", user)
                .replace("${version_name}", id)
                .replace("${game_directory}", ".")
                .replace("${assets_root}", assetsDir)
                .replace("${assets_index_name}", assetIndex == null ? "legacy" : assetIndex.id)
                .replace("${auth_uuid}", uuid)
                .replace("${auth_access_token}", token)
                .replace("${user_type}", "msa")
                .replace("${version_type}", "XuanZhi")
                .replace("${resolution_width}", "0")
                .replace("${resolution_height}", "0"));
        }
        return out;
    }
}
