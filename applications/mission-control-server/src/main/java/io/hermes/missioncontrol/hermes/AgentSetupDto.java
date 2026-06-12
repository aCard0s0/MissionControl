package io.hermes.missioncontrol.hermes;

import java.util.List;

public record AgentSetupDto(
    String envPath,
    boolean envExists,
    List<ApiKeyStatusDto> apiKeys,
    List<AuthProviderDto> authProviders,
    List<ApiKeyProviderDto> apiKeyProviders,
    List<MessagingStatusDto> messaging) {
}
