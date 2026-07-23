package com.example.chat.config;

import com.example.chat.service.UnslothAuthManager;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiModelsConfig {

    // Spring AI's OpenAI autoconfiguration registers its bean with @ConditionalOnMissingBean,
    // so adding the Unsloth bean below would silently suppress it. We declare both beans here
    // explicitly instead — one for LM Studio, one for Unsloth Studio — so ChatService can
    // inject each by qualifier.

    @Bean
    public OpenAiChatModel openAiChatModel(
            @Value("${spring.ai.openai.base-url:http://127.0.0.1:1234}") String baseUrl,
            @Value("${spring.ai.openai.api-key:lm-studio}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:default}") String defaultModel,
            @Value("${spring.ai.openai.chat.options.temperature:0.7}") Double temperature) {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(new SimpleApiKey(apiKey))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(defaultModel)
                        .temperature(temperature)
                        .build())
                .build();
    }

    @Bean
    public OpenAiChatModel unslothChatModel(
            @Value("${unsloth.base-url:http://127.0.0.1:8888}") String baseUrl,
            UnslothAuthManager authManager) {

        // Dynamic ApiKey — Spring AI calls getValue() on every request, so we hand
        // back a freshly refreshed JWT each time without needing to rebuild the bean.
        ApiKey apiKey = () -> {
            String token = authManager.getAccessToken();
            return token != null ? token : "";
        };

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("default")
                        .temperature(0.7)
                        .build())
                .build();
    }
}
