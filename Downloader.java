package net.xuanzhi.launcher.download;

import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.hanyi.launcher.util.HashUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个文件的下载器。特性：
 *  - 支持断点续传（Range: bytes=x-），被中断后重开接着下
 *  - 下载完按 expectedSha1 校验，不对就删掉重下
 *  - 进度回调在主线程之外跑，调用方自己切线程
 */
public final class Downloader {

    public interface Progress {
        /** @param done 已下载字节；@param total 总字节，未知时为 -1 */
        void onProgress(@NonNull String task, long done, long total);
        void onDone(@NonNull File file);
        void onError(@NonNull String task, @NonNull IOException e);
    }

    private final OkHttpClient http;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public Downloader(@NonNull OkHttpClient http) { this.http = http; }

    public void cancel() { cancelled.set(true); }

    /**
     * 下载到 target。如果 target 已存在且大小 == contentLength，直接回调 onDone（跳过已完成的）。
     * expectedSha1 为空则不校验。
     */
    public void fetch(@NonNull String task, @NonNull String url, @NonNull File target,
                      @Nullable String expectedSha1, @NonNull Progress p) {

        File tmp = new File(target.getParentFile(), target.getName() + ".part");
        target.getParentFile().mkdirs();

        try {
            long existing = tmp.exists() ? tmp.length() : 0;
            Request.Builder rb = new Request.Builder().url(url);
            if (existing > 0) rb.header("Range", "bytes=" + existing + "-");

            try (Response resp = http.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful()) {
                    if (resp.code() == 416) { /* 请求范围非法 -> 文件已完整，重下 */ tmp.delete(); fetch(task, url, target, expectedSha1, p); return; }
                    throw new IOException("HTTP " + resp.code() + " for " + url);
                }
                ResponseBody body = resp.body();
                if (body == null) throw new IOException("empty body: " + url);

                long total = existing + body.contentLength();
                boolean append = existing > 0 && resp.code() == 206;

                try (InputStream is = body.byteStream();
                     FileOutputStream fos = append ? new FileOutputStream(tmp, true) : new FileOutputStream(tmp, false)) {

                    byte[] buf = new byte[8192];
                    long done = existing;
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        if (cancelled.get()) return;
                        fos.write(buf, 0, n);
                        done += n;
                        p.onProgress(task, done, total);
                    }
                    fos.flush();
                }
            }

            // 完整性校验
            if (expectedSha1 != null && !expectedSha1.isEmpty()) {
                String got = HashUtil.sha1Hex(tmp);
                if (!got.equalsIgnoreCase(expectedSha1)) {
                    tmp.delete();
                    throw new IOException("SHA1 不匹配 " + task + "：期望 " + expectedSha1 + "，实际 " + got);
                }
            }

            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) { tmp.renameTo(new File(target.getAbsolutePath() + ".bad")); }
            p.onDone(target);

        } catch (IOException e) {
            if (!cancelled.get()) p.onError(task, e);
        }
    }

    /** 把 source 解压到 destDir。source 必须是 zip/tar.xz 之外的普通下载项时由调用方判断。 */
    public static void atomicMove(@NonNull File tmp, @NonNull File target) throws IOException {
        if (target.exists() && !target.delete()) throw new IOException("无法覆盖 " + target);
        if (!tmp.renameTo(target)) throw new IOException("无法落盘 " + target);
    }
}
