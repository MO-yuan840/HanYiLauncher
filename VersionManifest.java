package net.xuanzhi.launcher.meta;

import androidx.annotation.NonNull;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 对应 https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
 * 只留启动器需要的字段，其余 Jackson 自动忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionManifest {

    public Latest latest;
    public List<Entry> versions;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Latest {
        public String release;
        public String snapshot;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        @NonNull public String id = "";
        @NonNull public String type = "release";   // release / snapshot / old_beta / old_alpha
        @NonNull public String url = "";           // 该版本 version.json 的地址
        @NonNull public String sha1 = "";
        public long releaseTime;

        public boolean isRelease() { return "release".equals(type); }
        public String display() { return id + ("release".equals(type) ? "" : "  (" + type + ")"); }
    }
}
