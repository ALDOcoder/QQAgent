package com.qqagent.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqagent.config.LlmProperties;
import com.qqagent.inbound.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * llm.enabled=true 时接管回复。所有失败路径都回一句不花钱的兜底文案，绝不抛异常打断 QQ 链路。
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class LlmBrain implements Brain {

    private static final Logger log = LoggerFactory.getLogger(LlmBrain.class);

    private final LlmProperties props;
    private final ConversationStore conversations;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    public LlmBrain(LlmProperties props, ConversationStore conversations, BudgetGuard budget, ObjectMapper mapper) {
        this.props = props;
        this.conversations = conversations;
        this.budget = budget;
        this.mapper = mapper;
    }

    @Override
    public String reply(InboundMessage message) {
        if (!props.hasKey()) {
            log.error("llm.enabled=true 但没有 llm.api-key，回兜底文案");
            return "我还没配好模型密钥";
        }
        if (!budget.allowCall()) {
            return "今天的额度用完了，明天再来找我吧";
        }

        budget.countCall();
        try {
            String payload = buildPayload(message);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.error("模型返回 HTTP {} {}", resp.statusCode(), trim(resp.body()));
                return "我这边模型调用失败了，稍后再试";
            }

            JsonNode json = mapper.readTree(resp.body());
            String content = json.path("choices").path(0).path("message").path("content").asText("");
            int promptTokens = json.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = json.path("usage").path("completion_tokens").asInt(0);
            budget.recordUsage(promptTokens, completionTokens);

            // 思维链吃光额度时 content 是空的，这是推理型模型的已知坑
            if (content.isBlank()) {
                log.warn("模型返回空正文（reasoning 占满 max_tokens={}？）", props.getMaxOutputTokens());
                return "我想了想但没想出来，换个说法试试？";
            }

            conversations.append(message.openid(), "user", message.text(), props.getHistoryTurns());
            conversations.append(message.openid(), "assistant", content, props.getHistoryTurns());
            return content;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "我走神了，再说一次？";
        } catch (Exception e) {
            log.error("模型调用异常: {}", e.getMessage());
            return "模型连不上，稍后再试";
        }
    }

    private String buildPayload(InboundMessage message) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxOutputTokens());
        body.put("reasoning_effort", props.getReasoningEffort());

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", props.getSystemPrompt());
        for (String[] turn : conversations.context(message.openid(), props.getHistoryTurns())) {
            messages.addObject().put("role", turn[0]).put("content", turn[1]);
        }
        messages.addObject().put("role", "user").put("content", message.text());

        return mapper.writeValueAsString(body);
    }

    private static String trim(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
