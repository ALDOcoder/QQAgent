package com.qqagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "qq")
public class QqProperties {

    private String appId = "";
    private String clientSecret = "";
    private String apiBase = "https://api.bot.qq.com";
    private String tokenPath = "/app/getAppAccessToken";
    private String gatewayPath = "/gateway";
    private long intents = 33554432L;
    private int shardIndex = 0;
    private int shardTotal = 1;
    private boolean dryRun = true;
    private List<String> allowedOpenIds = new ArrayList<>();

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getTokenPath() {
        return tokenPath;
    }

    public void setTokenPath(String tokenPath) {
        this.tokenPath = tokenPath;
    }

    public String getGatewayPath() {
        return gatewayPath;
    }

    public void setGatewayPath(String gatewayPath) {
        this.gatewayPath = gatewayPath;
    }

    public long getIntents() {
        return intents;
    }

    public void setIntents(long intents) {
        this.intents = intents;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public void setShardIndex(int shardIndex) {
        this.shardIndex = shardIndex;
    }

    public int getShardTotal() {
        return shardTotal;
    }

    public void setShardTotal(int shardTotal) {
        this.shardTotal = shardTotal;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public List<String> getAllowedOpenIds() {
        return allowedOpenIds;
    }

    public void setAllowedOpenIds(List<String> allowedOpenIds) {
        this.allowedOpenIds = allowedOpenIds;
    }

    public boolean hasCredentials() {
        return appId != null && !appId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
