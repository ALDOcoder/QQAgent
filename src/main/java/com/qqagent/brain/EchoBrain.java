package com.qqagent.brain;

import com.qqagent.inbound.InboundMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 零成本大脑：跑通链路用，不花一分钱 API 费。llm.enabled=true 时让位给 LlmBrain。
 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "false", matchIfMissing = true)
public class EchoBrain implements Brain {

    @Override
    public String reply(InboundMessage message) {
        return "收到了: " + message.text();
    }
}
