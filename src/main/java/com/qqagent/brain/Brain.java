package com.qqagent.brain;

import com.qqagent.inbound.InboundMessage;

public interface Brain {

    String reply(InboundMessage message);
}
