package com.qqagent.policy;

import com.qqagent.config.QqProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReplyPolicy {

    private final QqProperties props;

    public ReplyPolicy(QqProperties props) {
        this.props = props;
    }

    public boolean allow(String openid) {
        List<String> allowed = props.getAllowedOpenIds();
        return allowed.isEmpty() || allowed.contains(openid);
    }
}
