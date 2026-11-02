package com.qqagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqagent.config.QqProperties;
import com.qqagent.inbound.EventDispatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QQ 官方 WebSocket 网关客户端：取 wss 地址 -> OP 2 鉴权 -> 按 hello 给的间隔心跳 -> 断线退避重连并尝试 OP 6 恢复。
 */
@Component
public class GatewayClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);

    static final int OP_DISPATCH = 0;
    static final int OP_HEARTBEAT = 1;
    static final int OP_IDENTIFY = 2;
    static final int OP_RESUME = 6;
    static final int OP_RECONNECT = 7;
    static final int OP_INVALID_SESSION = 9;
    static final int OP_HELLO = 10;
    static final int OP_HEARTBEAT_ACK = 11;

    private final QqProperties props;
    private final AccessTokenService tokens;
    private final EventDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    private final ThreadFactory threads = r -> {
        Thread t = new Thread(r, "qq-gateway");
        t.setDaemon(true);
        return t;
    };
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, threads);

    private volatile WebSocket socket;
    private volatile Long lastSeq;
    private volatile String sessionId;
    private volatile long heartbeatMs = 45_000L;
    private volatile boolean canResume = true;
    private volatile int attempt = 0;

    private ScheduledFuture<?> heartbeatTask;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    public GatewayClient(QqProperties props, AccessTokenService tokens, EventDispatcher dispatcher, ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        if (!props.hasCredentials()) {
            log.warn("未配置 qq.app-id / qq.client-secret，跳过网关连接。"
                    + "现在仍可用 POST /debug/c2c 验证接收->处理->回复这条链路。");
            return;
        }
        connect();
    }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        exec.submit(this::doConnect);
    }

    private void doConnect() {
        try {
            String token = tokens.get();
            String wssUrl = fetchWssUrl(token);
            log.info("连接网关 {}", wssUrl);
            socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wssUrl), new FrameListener())
                    .join();
            attempt = 0;
        } catch (Exception e) {
            log.error("网关连接失败: {}", rootMessage(e));
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    private String fetchWssUrl(String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getApiBase() + props.getGatewayPath()))
                .header("Authorization", "QQBot " + token)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("取 wss 地址失败 HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body()).path("url").asText();
    }

    private void handleFrame(String raw, WebSocket ws) {
        try {
            JsonNode frame = mapper.readTree(raw);
            int op = frame.path("op").asInt(-1);
            log.debug("<< op={} t={} {}", op, frame.path("t").asText(""), trim(raw));

            switch (op) {
                case OP_HELLO -> {
                    heartbeatMs = frame.path("d").path("heartbeat_interval").asLong(45_000L);
                    startHeartbeat();
                    if (canResume && sessionId != null && lastSeq != null) {
                        send(frame(OP_RESUME, resumePayload()), ws);
                    } else {
                        send(frame(OP_IDENTIFY, identifyPayload()), ws);
                    }
                }
                case OP_DISPATCH -> {
                    lastSeq = frame.path("s").asLong();
                    String type = frame.path("t").asText("");
                    if ("READY".equals(type)) {
                        sessionId = frame.path("d").path("session_id").asText(null);
                        canResume = true;
                        log.info("鉴权成功 session_id={} 订阅 intents={}", sessionId, props.getIntents());
                    }
                    dispatcher.dispatch(type, frame.path("d"));
                }
                case OP_HEARTBEAT_ACK -> log.trace("心跳确认 seq={}", lastSeq);
                case OP_RECONNECT -> {
                    log.warn("网关要求重连 (op 7)");
                    closeQuietly();
                    scheduleReconnect();
                }
                case OP_INVALID_SESSION -> {
                    log.warn("会话失效 (op 9)，丢弃 session 重新鉴权");
                    canResume = false;
                    sessionId = null;
                    lastSeq = null;
                    send(frame(OP_IDENTIFY, identifyPayload()), ws);
                }
                default -> log.debug("忽略 op={}", op);
            }
        } catch (Exception e) {
            log.error("处理帧失败: {}", e.getMessage());
        }
    }

    private JsonNode identifyPayload() {
        var node = mapper.createObjectNode();
        node.put("token", "QQBot " + tokens.get());
        node.put("intents", props.getIntents());
        node.set("shard", mapper.valueToTree(List.of(props.getShardIndex(), props.getShardTotal())));
        return node;
    }

    private JsonNode resumePayload() {
        var node = mapper.createObjectNode();
        node.put("token", "QQBot " + tokens.get());
        node.put("session_id", sessionId);
        node.put("seq", lastSeq);
        return node;
    }

    private JsonNode frame(int op, JsonNode d) {
        var node = mapper.createObjectNode();
        node.put("op", op);
        node.set("d", d);
        return node;
    }

    private void send(JsonNode frame, WebSocket ws) {
        if (ws == null || ws.isOutputClosed()) {
            log.warn("连接不可用，丢弃出站帧 op={}", frame.path("op").asInt());
            return;
        }
        try {
            String json = mapper.writeValueAsString(frame);
            log.debug(">> {}", trim(maskToken(frame)));
            ws.sendText(json, true).join();
        } catch (Exception e) {
            log.error("发送失败: {}", rootMessage(e));
        }
    }

    private void startHeartbeat() {
        ScheduledFuture<?> old = heartbeatTask;
        if (old != null) {
            old.cancel(false);
        }
        // 首个心跳前加随机抖动，避免重连风暴时所有客户端同时打服务器
        long jitter = TimeUnit.SECONDS.toMillis(ThreadLocalRandom.current().nextInt(1, 6));
        heartbeatTask = exec.scheduleAtFixedRate(
                () -> send(heartbeatFrame(), socket),
                heartbeatMs / 2 + jitter,
                heartbeatMs,
                TimeUnit.MILLISECONDS);
    }

    private JsonNode heartbeatFrame() {
        var node = mapper.createObjectNode();
        node.put("op", OP_HEARTBEAT);
        if (lastSeq == null) {
            node.putNull("d");
        } else {
            node.put("d", lastSeq);
        }
        return node;
    }

    private void scheduleReconnect() {
        stopHeartbeat();
        long delay = Math.min(60_000L, 1_000L << Math.min(attempt++, 6));
        log.info("{} ms 后重连（第 {} 次）", delay, attempt);
        exec.schedule(this::doConnect, delay, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void closeQuietly() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").join();
            } catch (Exception ignored) {
                ws.abort();
            }
        }
    }

    @PreDestroy
    void shutdown() {
        stopHeartbeat();
        closeQuietly();
        exec.shutdownNow();
    }

    private static String trim(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "...(共" + s.length() + "字符)" : s;
    }

    /** IDENTIFY/RESUME 帧里带 access_token，日志必须脱敏，否则凭据落盘到 app.log。 */
    private String maskToken(JsonNode frame) throws Exception {
        JsonNode d = frame.path("d");
        if (!d.has("token")) {
            return mapper.writeValueAsString(frame);
        }
        JsonNode copy = frame.deepCopy();
        ((ObjectNode) copy.path("d")).put("token", "QQBot ******已脱敏");
        return mapper.writeValueAsString(copy);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** 一帧可能被拆成多段，必须攒到 last=true 再解析。 */
    private final class FrameListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                handleFrame(raw, ws);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("网关关闭连接 status={} reason={}", statusCode, reason);
            canResume = true;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("网关连接异常: {}", rootMessage(error));
            canResume = true;
            scheduleReconnect();
        }
    }
}
