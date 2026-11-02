package com.qqagent.debug;

import com.qqagent.config.QqProperties;
import com.qqagent.inbound.EventDispatcher;
import com.qqagent.inbound.InboundMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * 本地调试门：没有 QQ 凭据时把一条假消息打进真实处理链路。
 * 只在 dry-run 下开放，避免真发阶段被人从局域网驱使机器人发消息。
 */
@RestController
@RequestMapping("/debug")
public class DebugController {

    private final EventDispatcher dispatcher;
    private final QqProperties props;

    public DebugController(EventDispatcher dispatcher, QqProperties props) {
        this.dispatcher = dispatcher;
        this.props = props;
    }

    @PostMapping("/c2c")
    public Map<String, Object> c2c(@RequestParam String text,
                                   @RequestParam(defaultValue = "debug-openid") String openid) {
        if (!props.isDryRun()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "调试端点仅在 qq.dry-run=true 时开放");
        }
        String messageId = "debug-" + UUID.randomUUID();
        dispatcher.handle(new InboundMessage(openid, messageId, text));
        return Map.of("messageId", messageId, "dryRun", true);
    }
}
