package com.qqagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** false = 用 EchoBrain（零花费）；true = 用 LlmBrain。切换靠它，不用改代码。 */
    private boolean enabled = false;
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-flash";
    /** 推理型模型：思维链会吃掉额度，太小会回空字符串。 */
    private int maxOutputTokens = 800;
    private String reasoningEffort = "low";
    private int historyTurns = 6;
    private String systemPrompt = "你是一个 QQ 上的私人小助手，回答简短、口语化，用中文，不要使用 Markdown 语法。";

    /** 单价：元 / 百万 token，只用于日志里估算花费。 */
    private double priceInPerMillion = 1.0;
    private double priceOutPerMillion = 2.0;

    /** 账单硬闸门 */
    private int dailyReplyLimit = 200;
    private int timeoutSeconds = 45;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public int getHistoryTurns() {
        return historyTurns;
    }

    public void setHistoryTurns(int historyTurns) {
        this.historyTurns = historyTurns;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public double getPriceInPerMillion() {
        return priceInPerMillion;
    }

    public void setPriceInPerMillion(double priceInPerMillion) {
        this.priceInPerMillion = priceInPerMillion;
    }

    public double getPriceOutPerMillion() {
        return priceOutPerMillion;
    }

    public void setPriceOutPerMillion(double priceOutPerMillion) {
        this.priceOutPerMillion = priceOutPerMillion;
    }

    public int getDailyReplyLimit() {
        return dailyReplyLimit;
    }

    public void setDailyReplyLimit(int dailyReplyLimit) {
        this.dailyReplyLimit = dailyReplyLimit;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
