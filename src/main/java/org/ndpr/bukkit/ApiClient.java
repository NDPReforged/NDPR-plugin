package org.ndpr.bukkit;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 客户端封装 (JDK HttpClient, 无需额外依赖)
 * 约定: 返回 null = 网络错误; 抛 HttpStatusException = 非 200 响应
 */
public class ApiClient {

    /** 非 200 响应 */
    public static class HttpStatusException extends Exception {
        public final int status;
        public final String body;

        public HttpStatusException(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
            this.body = body;
        }
    }

    private final HttpClient http;
    private final NDPRPlugin plugin;

    public ApiClient(NDPRPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private String baseUrl() {
        return plugin.ndprConfig().apiUrl();
    }

    private Map<String, String> authHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + plugin.ndprConfig().token());
        headers.put("Content-Type", "application/json");
        return headers;
    }

    // ============ JSON helpers ============

    /** POST 无鉴权; 网络错误返回 null */
    public JsonObject post(String path, JsonObject payload, int timeoutSec) throws HttpStatusException {
        return postJson(baseUrl() + path, payload, null, timeoutSec);
    }

    /** POST 带 Bearer 鉴权; 网络错误返回 null */
    public JsonObject postAuth(String path, JsonObject payload, int timeoutSec) throws HttpStatusException {
        return postJson(baseUrl() + path, payload, authHeaders(), timeoutSec);
    }

    /** GET 无鉴权 */
    public JsonObject get(String path, int timeoutSec) throws HttpStatusException {
        return getJson(baseUrl() + path, null, timeoutSec);
    }

    /** GET 带 Bearer 鉴权 */
    public JsonObject getAuth(String path, int timeoutSec) throws HttpStatusException {
        return getJson(baseUrl() + path, authHeaders(), timeoutSec);
    }

    /** GET 任意 URL(如 GitHub API) */
    public JsonObject getPlain(String url, int timeoutSec) throws HttpStatusException {
        return getJson(url, null, timeoutSec);
    }

    // ============ Raw ============

    public JsonObject postJson(String url, JsonObject payload, Map<String, String> headers, int timeoutSec) throws HttpStatusException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "{}" : payload.toString()));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new HttpStatusException(resp.statusCode(), resp.body());
            }
            if (resp.body() == null || resp.body().isEmpty()) {
                return null;
            }
            return com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (HttpStatusException e) {
            throw e;
        } catch (Exception e) {
            return null; // 网络错误
        }
    }

    public JsonObject getJson(String url, Map<String, String> headers, int timeoutSec) throws HttpStatusException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .GET();
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new HttpStatusException(resp.statusCode(), resp.body());
            }
            if (resp.body() == null || resp.body().isEmpty()) {
                return null;
            }
            return com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (HttpStatusException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    /** 下载二进制内容(封禁数据库文件) */
    public byte[] getBytes(String url, int timeoutSec) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .GET()
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
