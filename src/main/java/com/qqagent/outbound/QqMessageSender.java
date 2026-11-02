package com.qqagent.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqagent.config.QqProperties;
import com.qqagent.gateway.AccessTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 出站发送。单聊被动回复走 POST /v2/users/{user_openid}/messages，带 msg_id 表示"回复某条消息"。
 * 官方限制：单聊被动回复在收到消息后 60 分钟内有效、最多 4 次。
 */
@Component
public class  QqMessageSender {

    private static final Logger log = LoggerFactory.getLogger(QqMessageSender.class);

    private final QqProperties props;
    private final AccessTokenService tokens;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    public QqMessageSender(QqProperties props, AccessTokenService tokens, ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.mapper = mapper;
    }

    public void replyC2c(String openid, String messageId, String content) {
        if (props.isDryRun()) {
            log.info("[dry-run] 本应发给 {} >> {}", openid, content);
            return;
        }
        try {
            String body = mapper.writeValueAsString(new Request(content, messageId, 0));
            String url = props.getApiBase() + "/v2/users/"
                    + URLEncoder.encode(openid, StandardCharsets.UTF_8) + "/messages";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "QQBot " + tokens.get())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 401) {
                tokens.invalidate();
            }
            log.info("发送 {} HTTP {} {}", openid, resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("发送被中断");
        } catch (Exception e) {
            log.error("发送失败: {}", e.getMessage());
        }
    }

    private record Request(String content, String msg_id, int msg_type) {
    }
}
