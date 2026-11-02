package com.qqagent.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.qqagent.brain.Brain;
import com.qqagent.outbound.QqMessageSender;
import com.qqagent.policy.ReplyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final Brain brain;
    private final QqMessageSender sender;
    private final ReplyPolicy policy;

    public EventDispatcher(Brain brain, QqMessageSender sender, ReplyPolicy policy) {
        this.brain = brain;
        this.sender = sender;
        this.policy = policy;
    }

    public void dispatch(String type, JsonNode d) {
        switch (type) {
            case "C2C_MESSAGE_CREATE" -> onC2c(d);
            case "GROUP_AT_MESSAGE_CREATE" -> log.info("群 @ 事件（本阶段未实现）: {}", d.path("content").asText(""));
            case "READY", "RESUMED" -> log.debug("连接生命周期事件 {}", type);
            default -> {
                if (!type.isEmpty()) {
                    log.debug("未订阅处理的事件类型 {}", type);
                }
            }
        }
    }

    private void onC2c(JsonNode d) {
        String text = d.path("content").asText("").trim();
        String openid = d.path("author").path("user_openid").asText("");
        String messageId = d.path("id").asText("");
        if (text.isEmpty() || openid.isEmpty()) {
            return;
        }
        handle(new InboundMessage(openid, messageId, text));
    }

    /** 真实事件与调试注入共用这一段，保证测的是同一条链路。 */
    public void handle(InboundMessage message) {
        log.info("进来 [{}] openid={} text={}", message.messageId(), message.openid(), message.text());
        if (!policy.allow(message.openid())) {
            log.info("ReplyPolicy 拒绝 openid={}", message.openid());
            return;
        }
        String reply = brain.reply(message);
        sender.replyC2c(message.openid(), message.messageId(), reply);
    }
}
