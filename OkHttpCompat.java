package net.xuanzhi.launcher.util;

import okhttp3.OkHttpClient;

/** 单例 OkHttpClient：统一超时与连接池，避免每处 new 一个。 */
public final class OkHttpCompat {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build();
    public static OkHttpClient client() { return CLIENT; }
    private OkHttpCompat() {}
}
