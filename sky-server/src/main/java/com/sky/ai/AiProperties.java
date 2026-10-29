package com.sky.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("app.ai")
public class AiProperties {
    private String accessToken;
    private String chatBaseUrl;
    private String chatApiKey;
    private String chatModel;
}
