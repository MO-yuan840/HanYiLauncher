package net.xuanzhi.launcher.auth;

import androidx.annotation.NonNull;
import org.json.JSONObject;

/**
 * 启动器里一个「账号」。
 * 离线账号：uuid 本地随机生成，token 为空，进离线服务器/单机没问题，进正版服会被拒。
 * 微软账号：走 MS OAuth -> XBL -> XSTS -> Minecraft Services，拿到 mcToken + 皮肤 uuid。
 */
public abstract class Account {

    public enum Kind { OFFLINE, MICROSOFT }

    @NonNull public String localId = "";     // 本地唯一键
    @NonNull public String displayName = ""; // 游戏内显示名
    @NonNull public String uuid = "";        // 正版 uuid（离线为随机）
    @NonNull public String mcToken = "";     // Minecraft access token；离线为空
    public long expiresAt = 0;               // mcToken 过期时间 ms

    public abstract Kind kind();

    public boolean isOnline() { return kind() == Kind.MICROSOFT; }
    public boolean isExpired() { return isOnline() && System.currentTimeMillis() > expiresAt; }

    /** 序列化到持久化存储（SharedPreferences -> JSON）。不存明文密码。 */
    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("localId", localId);
        o.put("displayName", displayName);
        o.put("uuid", uuid);
        o.put("kind", kind().name());
        o.put("mcToken", mcToken);
        o.put("expiresAt", expiresAt);
        return o;
    }

    public static Account fromJson(@NonNull JSONObject o) {
        Kind k = Kind.valueOf(o.optString("kind", "OFFLINE"));
        if (k == Kind.MICROSOFT) {
            MicrosoftAccount a = new MicrosoftAccount();
            a.localId   = o.optString("localId");
            a.displayName = o.optString("displayName");
            a.uuid      = o.optString("uuid");
            a.mcToken   = o.optString("mcToken", "");
            a.expiresAt = o.optLong("expiresAt", 0);
            a.msaRefreshToken = o.optString("msaRefreshToken", "");
            a.xuid      = o.optString("xuid", "");
            return a;
        }
        OfflineAccount a = new OfflineAccount();
        a.localId   = o.optString("localId");
        a.displayName = o.optString("displayName");
        a.uuid      = o.optString("uuid");
        return a;
    }

    /** 启动参数里要用的「玩家名」 */
    @NonNull public String authPlayerName() { return displayName; }
    /** 启动参数里要用的 uuid 字段（微软要求无横杠的 lower hex） */
    @NonNull public String authUuid() { return uuid.replace("-", "").toLowerCase(); }
    /** 启动参数里要用的 access token；离线给个占位，服务端自己判 */
    @NonNull public String authToken() { return mcToken.isEmpty() ? "0" : mcToken; }

    // ---------- 两种实现 ----------

    public static class OfflineAccount extends Account {
        @Override public Kind kind() { return Kind.OFFLINE; }
    }

    public static class MicrosoftAccount extends Account {
        @NonNull public String msaRefreshToken = "";  // 刷新用，加密存储
        @NonNull public String xuid = "";
        /** 需要走 refresh 刷新 mcToken 吗 */
        public boolean canRefresh() { return !msaRefreshToken.isEmpty() && isExpired(); }
        @Override public Kind kind() { return Kind.MICROSOFT; }
    }
}
