package com.qqagent.brain;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 openid 分桶的短期记忆，纯内存，进程重启即清空。上限按"轮"算，1 轮 = 一问一答两条消息。
 */
@Component
public class ConversationStore {

    private final Map<String, Deque<String[]>> history = new ConcurrentHashMap<>();

    /** 取当前上下文（不含 system），最旧的在前。 */
    public List<String[]> context(String openid, int maxTurns) {
        Deque<String[]> deque = history.get(openid);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void append(String openid, String role, String content, int maxTurns) {
        Deque<String[]> deque = history.computeIfAbsent(openid, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new String[]{role, content});
            while (deque.size() > maxTurns * 2) {
                deque.removeFirst();
            }
        }
    }

    public void clear(String openid) {
        Deque<String[]> deque = history.remove(openid);
        if (deque != null) {
            synchronized (deque) {
                deque.clear();
            }
        }
    }
}
