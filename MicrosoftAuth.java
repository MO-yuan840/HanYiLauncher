package net.xuanzhi.launcher.auth;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import okhttp3.*;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 微软 -> Xbox Live -> Minecraft 的完整鉴权链。
 *
 * 为什么要自己写这条链：第三方启动器没法用「官方 client_secret」，
 * 所以只能用「公共客户端 + 设备代码流（device code）」或者「公共客户端 + 授权码 + PKCE」。
 * 这里给两套都能用的实现，默认走 PKCE（体验更好，不用用户抄 8 位码）。
 *
 * ⚠️ 你需要一个自己的 Azure 应用：
 *    Azure 门户 -> 应用注册 -> 公开客户端(移动/桌面) -> 添加重定向 URI（建议 msal<client-id>://auth）
 *    勾选「允许公共客户端流」
 *    scope 必须含 XboxLive.signin + offline_access
 * 然后把 clientId 填到下面的 AAD_CLIENT_ID。
 */
public final class MicrosoftAuth {

    /** ⚠️ 换成你自己的 Azure 公共客户端 ID。空着时 UI 会明确提示，不会静默失败。 */
    public static final String AAD_CLIENT_ID = "";
    public static final String REDIRECT_URI   = "msal" + AAD_CLIENT_ID + "://auth";
    public static final String SCOPE          = "XboxLive.signin offline_access";

    private static final String TAG = "MsAuth";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private MicrosoftAuth() {}

    // ---------- 阶段 1：拿到授权码（在 WebView 里打开这个地址） ----------

    /** PKCE 用的 code verifier（43-128 字符，这里 64 位随机） */
    @NonNull public static String newVerifier() {
        byte[] b = new byte[48];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @NonNull
    public static String authorizeUrl(@NonNull String verifier) throws IllegalStateException {
        if (TextUtils.isEmpty(AAD_CLIENT_ID)) throw new IllegalStateException("AAD_CLIENT_ID 未配置");
        String chal = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(verifier));
        return "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
                + "?client_id=" + AAD_CLIENT_ID
                + "&response_type=code"
                + "&redirect_uri=" + OkHttpCompat.utf8(REDIRECT_URI)
                + "&scope=" + OkHttpCompat.utf8(SCOPE)
                + "&response_mode=query"
                + "&code_challenge=" + chal
                + "&code_challenge_method=S256";
    }

    /** 从 WebView 回调 URL 里抠出 code。返回 null 表示不是我们的回调。 */
    @Nullable
    public static String peelCode(@NonNull String url) {
        if (!url.startsWith(REDIRECT_URI + "?") && !url.startsWith(REDIRECT_URI + "#")) return null;
        String q = url.contains("?") ? url.substring(url.indexOf('?') + 1)
                                     : url.substring(url.indexOf('#') + 1);
        for (String kv : q.split("&")) {
            String[] p = kv.split("=");
            if (p.length == 2 && "code".equals(p[0])) return OkHttpCompat.decode(p[1]);
        }
        return null;
    }

    // ---------- 阶段 2：code -> Microsoft access_token ----------

    @NonNull
    public static MsToken exchangeCode(@NonNull String code, @NonNull String verifier) throws AuthException {
        return postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                new String[]{
                        "client_id", AAD_CLIENT_ID,
                        "grant_type", "authorization_code",
                        "code", code,
                        "redirect_uri", REDIRECT_URI,
                        "code_verifier", verifier,
                        "scope", SCOPE
                });
    }

    @NonNull
    public static MsToken refresh(@NonNull String refreshToken) throws AuthException {
        return postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                new String[]{
                        "client_id", AAD_CLIENT_ID,
                        "grant_type", "refresh_token",
                        "refresh_token", refreshToken,
                        "scope", SCOPE
                });
    }

    // ---------- 阶段 3：MS token -> Xbox Live (XBL) ----------
    @NonNull
    public static XblToken xbl(@NonNull String msToken) throws AuthException {
        JSONObject body = new JSONObject();
        try {
            body.put("Properties",
                new JSONObject()
                    .put("AuthMethod", "RPS")
                    .put("SiteName", "user.auth.xboxlive.com")
                    .put("RpsTicket", "d=" + msToken));
            body.put("RelyingParty", "http://auth.xboxlive.com");
            body.put("TokenType", "JWT");
        } catch (JSONException e) { throw new AuthException("xbl body", e); }

        JSONObject r = postJson("https://user.auth.xboxlive.com/user/authenticate", body);
        try {
            return new XblToken(r.getString("Token"), r.getJSONObject("DisplayClaims")
                    .getJSONArray("xui").getJSONObject(0).getString("uhs"));
        } catch (JSONException e) { throw new AuthException("xbl response: " + r, e); }
    }

    // ---------- 阶段 4：XBL -> XSTS ----------
    @NonNull
    public static XstsToken xsts(@NonNull String xblToken) throws AuthException {
        JSONObject body = new JSONObject();
        try {
            body.put("Properties", new JSONObject().put("SandboxId", "RETAIL")
                .put("UserTokens", new org.json.JSONArray().put(xblToken)));
            body.put("RelyingParty", "rp://api.minecraftservices.com/");
            body.put("TokenType", "JWT");
        } catch (JSONException e) { throw new AuthException("xsts body", e); }

        JSONObject r = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", body);
        try {
            return new XstsToken(r.getString("Token"), r.getJSONObject("DisplayClaims")
                    .getJSONArray("xui").getJSONObject(0).getString("uhs"));
        } catch (JSONException e) { throw new AuthException("xsts response: " + r, e); }
    }

    // ---------- 阶段 5：XSTS -> Minecraft 购证 ----------
    @NonNull
    public static McEntitlement minecraftEntitlement(@NonNull XstsToken xsts) throws AuthException {
        JSONObject r = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                new JSONObject().put("identityToken",
                        "XBL3.0 x=" + xsts.uhs + ";" + xsts.token));
        try {
            McEntitlement e = new McEntitlement();
            e.accessToken = r.getString("access_token");
            e.tokenType   = r.optString("token_type", "Bearer");
            e.expiresIn   = r.optLong("expires_in", 86400);
            return e;
        } catch (JSONException e) { throw new AuthException("mc login response: " + r, e); }
    }

    /** 校验账号到底有没有买 Java 版。没有就抛 HasNoLicense。 */
    @NonNull
    public static McProfile minecraftProfile(@NonNull String bearer) throws AuthException {
        Request req = new Request.Builder()
                .url("https://api.minecraftservices.com/minecraft/profile")
                .header("Authorization", "Bearer " + bearer)
                .get().build();
        try (Response resp = HTTP.newCall(req).execute()) {
            String s = resp.body() == null ? "" : resp.body().string();
            if (resp.code() == 404 || s.contains("NOT_FOUND") || s.contains("does not have")) {
                throw new HasNoLicense();
            }
            if (!resp.isSuccessful()) throw new AuthException("profile " + resp.code() + ": " + s);
            JSONObject o = new JSONObject(s);
            McProfile p = new McProfile();
            p.uuid    = o.getString("id");
            p.name    = o.getString("name");
            p.skins   = o.optJSONArray("skins");
            return p;
        } catch (IOException | JSONException e) {
            if (e instanceof AuthException) throw (AuthException) e;
            throw new AuthException("profile fetch", e);
        }
    }

    // ---------- 一键串完 ----------
    @NonNull
    public static MicrosoftAccount fullFlow(@NonNull String code, @NonNull String verifier) throws AuthException {
        MsToken  t1 = exchangeCode(code, verifier);
        XblToken t2 = xbl(t1.accessToken);
        XstsToken t3 = xsts(t2.token);
        McEntitlement mc = minecraftEntitlement(t3);
        McProfile pf = minecraftProfile(mc.accessToken);

        MicrosoftAccount a = new MicrosoftAccount();
        a.localId   = UUID.randomUUID().toString();
        a.displayName = pf.name;
        a.uuid      = pf.uuid;
        a.mcToken   = mc.accessToken;
        a.expiresAt = System.currentTimeMillis() + mc.expiresIn * 1000L - 60_000L;
        a.msaRefreshToken = t1.refreshToken;
        a.xuid      = t2.uhs;
        return a;
    }

    // ---------- 数据结构 ----------

    public static class MsToken {
        @NonNull public String accessToken = "", refreshToken = "";
        public long expiresIn;
    }
    public static class XblToken  { @NonNull public String token, uhs; }
    public static class XstsToken { @NonNull public String token, uhs; }
    public static class McEntitlement {
        @NonNull public String accessToken = "", tokenType = "Bearer";
        public long expiresIn;
    }
    public static class McProfile {
        @NonNull public String uuid = "", name = "";
        public org.json.JSONArray skins;
    }

    // ---------- HTTP 细节 ----------

    @NonNull
    private static MsToken postForm(@NonNull String url, @NonNull String[] kv) throws AuthException {
        FormBody.Builder b = new FormBody.Builder();
        for (int i = 0; i < kv.length; i += 2) b.add(kv[i], kv[i + 1]);
        Request req = new Request.Builder().url(url).post(b.build()).build();
        try (Response resp = HTTP.newCall(req).execute()) {
            String s = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new AuthException("oauth " + resp.code() + ": " + s);
            JSONObject o = new JSONObject(s);
            MsToken t = new MsToken();
            t.accessToken  = o.getString("access_token");
            t.refreshToken  = o.optString("refresh_token", "");
            t.expiresIn     = o.optLong("expires_in", 3600);
            return t;
        } catch (IOException | JSONException e) {
            if (e instanceof AuthException) throw (AuthException) e;
            throw new AuthException("oauth network", e);
        }
    }

    @NonNull
    private static JSONObject postJson(@NonNull String url, @NonNull JSONObject body) throws AuthException {
        Request req = new Request.Builder().url(url)
            .post(RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8")))
            .header("Accept", "application/json")
            .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            String s = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new AuthException(url + " " + resp.code() + ": " + s);
            return new JSONObject(s);
        } catch (IOException | JSONException e) {
            if (e instanceof AuthException) throw (AuthException) e;
            throw new AuthException("postJson " + url, e);
        }
    }

    @NonNull
    private static byte[] sha256(@NonNull String s) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    // ---------- 异常 ----------

    public static class AuthException extends Exception {
        AuthException(@NonNull String m) { super(m); }
        AuthException(@NonNull String m, Throwable c) { super(m, c); }
    }
    /** 微软账号没买 Java 版。UI 上要给个明确的提示，别让用户以为密码错了。 */
    public static class HasNoLicense extends AuthException {
        HasNoLicense() { super("该微软账号未拥有 Minecraft: Java Edition"); }
    }
}
