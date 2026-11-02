package com.qqagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqagent.config.QqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * access_token 的获取与缓存。官方 expires_in 约 7200 秒，提前 5 分钟刷新，避免用到一个刚好过期的 token。
 */
@Service
public class AccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenService.class);
    private static final long REFRESH_AHEAD_MS = TimeUnit.MINUTES.toMillis(5);

    private final QqProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    private volatile String token;
    private volatile long expireAtEpochMs;

    public AccessTokenService(QqProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public synchronized String get() {
        if (token != null && System.currentTimeMillis() < expireAtEpochMs - REFRESH_AHEAD_MS) {
            return token;
        }
        return refresh();
    }

    /** 收到 401 时调用，强制下一次重新取 token。 */
    public synchronized void invalidate() {
        expireAtEpochMs = 0;
        token = null;
    }

    private String refresh() {
        try {
            String body = mapper.writeValueAsString(new Request(props.getAppId(), props.getClientSecret()));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getApiBase() + props.getTokenPath()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = mapper.readTree(resp.body());
            String accessToken = json.path("access_token").asText("");
            if (accessToken.isEmpty()) {
                throw new IllegalStateException("取 token 失败，HTTP " + resp.statusCode() + " 响应: " + resp.body());
            }
            long expiresIn = json.path("expires_in").asLong(7200L);

            token = accessToken;
            expireAtEpochMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(expiresIn);
            log.info("access_token 已刷新，有效期 {} 秒", expiresIn);
            return token;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("取 access_token 被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("取 access_token 异常: " + e.getMessage(), e);
        }
    }

    private record Request(String appId, String clientSecret) {
    }
}
