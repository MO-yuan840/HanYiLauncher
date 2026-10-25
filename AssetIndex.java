package net.xuanzhi.launcher.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 对应 assets/indexes/<id>.json。
 * objects 里的 value 是 { hash, size }；hash 的前两位做目录，
 * 下载地址：https://resources.download.minecraft.net/<hash前2位>/<hash>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssetIndex {

    public boolean virtual;
    public Map<String, Entry> objects;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        @JsonProperty("hash") public String hash;
        @JsonProperty("size") public long size;

        /** 本地路径：objects/<前两位>/<hash> */
        public String localPath() {
            return "objects/" + (hash == null || hash.length() < 2 ? hash : hash.substring(0, 2)) + "/" + hash;
        }
    }
}
