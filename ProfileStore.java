package net.xuanzhi.launcher.profile;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.hanyi.launcher.auth.Account;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地持久化：账号列表 + 当前激活账号 + 启动配置列表。
 * 用 SharedPreferences 存 JSON。生产级启动器会加密 refresh token（Android Keystore + EncryptedSharedPreferences），
 * 这里先把结构跑通，加密那层在 secureStore() 里留了接入点。
 */
public final class ProfileStore {

    private static final String FILE = "xuanzhi_state";
    private static final String KEY_ACCOUNTS   = "accounts";
    private static final String KEY_ACTIVE     = "active_account";
    private static final String KEY_PROFILES   = "profiles";
    private static final String KEY_BASE_DIR   = "base_dir";

    private final SharedPreferences prefs;

    public ProfileStore(@NonNull Context ctx) {
        prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- 账号 ----------

    @NonNull
    public List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_ACCOUNTS, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Account.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignore) {}
        return out;
    }

    @Nullable
    public Account accountByLocalId(@NonNull String id) {
        for (Account a : accounts()) if (a.localId.equals(id)) return a;
        return null;
    }

    public void upsert(@NonNull Account a) {
        Map<String, Account> m = new LinkedHashMap<>();
        for (Account x : accounts()) m.put(x.localId, x);
        m.put(a.localId, a);
        save(m.values());
    }

    public void remove(@NonNull String localId) {
        Map<String, Account> m = new LinkedHashMap<>();
        for (Account x : accounts()) if (!x.localId.equals(localId)) m.put(x.localId, x);
        save(m.values());
        if (localId.equals(prefs.getString(KEY_ACTIVE, ""))) prefs.edit().remove(KEY_ACTIVE).apply();
    }

    @Nullable
    public Account active() {
        String id = prefs.getString(KEY_ACTIVE, "");
        return id.isEmpty() ? null : accountByLocalId(id);
    }

    public void setActive(@Nullable Account a) {
        prefs.edit().putString(KEY_ACTIVE, a == null ? "" : a.localId).apply();
    }

    private void save(@NonNull Iterable<Account> all) {
        JSONArray a = new JSONArray();
        for (Account x : all) a.put(x.toJson());
        prefs.edit().putString(KEY_ACCOUNTS, a.toString()).apply();
    }

    // ---------- 启动配置 ----------

    @NonNull
    public List<LaunchProfile> profiles() {
        List<LaunchProfile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_PROFILES, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(LaunchProfile.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignore) {}
        return out;
    }

    public void saveProfiles(@NonNull List<LaunchProfile> ps) {
        JSONArray a = new JSONArray();
        for (LaunchProfile p : ps) a.put(p.toJson());
        prefs.edit().putString(KEY_PROFILES, a.toString()).apply();
    }

    // ---------- 基础目录 ----------

    @NonNull
    public String baseDir(@NonNull String fallbackInternal) {
        return prefs.getString(KEY_BASE_DIR, fallbackInternal);
    }

    public void setBaseDir(@NonNull String d) { prefs.edit().putString(KEY_BASE_DIR, d).apply(); }
}
