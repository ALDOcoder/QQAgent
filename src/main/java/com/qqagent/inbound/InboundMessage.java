package com.qqagent.inbound;

/**
 * 一条进来的消息。官方单聊用 user_openid 定位收件人，不是 QQ 号。
 *
 * @param openid    发送者的 user_openid
 * @param messageId 原消息 id，被动回复时要作为 msg_id 回传
 * @param text      文本内容
 */
public record InboundMessage(String openid, String messageId, String text) {
}
