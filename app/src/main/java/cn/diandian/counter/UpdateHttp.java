package cn.diandian.counter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Bounded HTTPS requests with explicit redirect validation and normal platform TLS checks. */
final class UpdateHttp {
    private UpdateHttp() { }

    static HttpURLConnection open(String address) throws IOException {
        String current = address;
        for (int hop = 0; hop < 6; hop++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
            if (!UpdateManifest.secureGithubUrl(current)) throw new IOException("更新服务器地址不受信任");
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
            connection.setUseCaches(false); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "DianDianCounter/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", "application/octet-stream, application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code;
            try { code = connection.getResponseCode(); }
            catch (IOException failed) { connection.disconnect(); throw failed; }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new IOException("更新服务器没有提供下载地址");
                current = new URL(new URL(current), location).toString(); continue;
            }
            if (code == 200) return connection;
            connection.disconnect();
            if (code == 404) throw new IOException("暂未发布可用的在线版本，请稍后重试");
            if (code == 403 || code == 429) throw new IOException("请求过于频繁，请稍后重试");
            throw new IOException("更新服务器暂不可用（" + code + "）");
        }
        throw new IOException("下载重定向次数过多");
    }

    static String text(String address, int maxBytes) throws IOException {
        HttpURLConnection connection = open(address);
        try {
            if (connection.getContentLengthLong() > maxBytes) throw new IOException("版本信息过大");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() + length > maxBytes) throw new IOException("版本信息过大");
                    output.write(buffer, 0, length);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
