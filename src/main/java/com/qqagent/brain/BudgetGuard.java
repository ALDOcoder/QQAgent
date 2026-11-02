package com.qqagent.brain;

import com.qqagent.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 账单闸门：他自掏预付费 key，超条数就停止调用模型，余额不足直接停。
 * 只挡"调模型"这一个动作，不挡发送，所以熔断时还能回一句固定文案（不花钱）。
 */
@Component
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    private final LlmProperties props;

    private LocalDate day = LocalDate.now();
    private int used;
    private double spentEstimate;

    public BudgetGuard(LlmProperties props) {
        this.props = props;
    }

    public synchronized boolean allowCall() {
        rolloverIfNeeded();
        if (used >= props.getDailyReplyLimit()) {
            log.warn("今日模型调用已达上限 {} 条，熔断（估算已花 {} 元）", used, fmt(spentEstimate));
            return false;
        }
        return true;
    }

    /** 调用发出前记账，避免失败请求漏计次数。 */
    public synchronized void countCall() {
        rolloverIfNeeded();
        used++;
    }

    public synchronized void recordUsage(int promptTokens, int completionTokens) {
        rolloverIfNeeded();
        double cost = promptTokens / 1_000_000.0 * props.getPriceInPerMillion()
                + completionTokens / 1_000_000.0 * props.getPriceOutPerMillion();
        spentEstimate += cost;
        log.info("本条用量 in={} out={} ≈{} 元；今日 {} 条，累计估算 {} 元",
                promptTokens, completionTokens, fmt(cost), used, fmt(spentEstimate));
    }

    private void rolloverIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            log.info("跨天重置额度：{} -> {}（昨日 {} 条 / 估算 {} 元）", day, today, used, fmt(spentEstimate));
            day = today;
            used = 0;
            spentEstimate = 0;
        }
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
