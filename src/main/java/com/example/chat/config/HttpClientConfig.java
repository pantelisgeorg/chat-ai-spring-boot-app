package com.example.chat.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    // Spring AI's OllamaApi uses the auto-configured WebClient.Builder for streaming chat.
    // The default Reactor Netty pool has no global response timeout, but proxies / dev
    // tools / OS sockets can still reap a connection that goes silent during a long
    // prompt-eval before the first token. We pin the settings explicitly so failure
    // modes are predictable: long connect, no response timeout, no idle reap.
    @Bean
    public WebClientCustomizer reactorNettyTimeoutsCustomizer() {
        ConnectionProvider pool = ConnectionProvider.builder("ai-stream")
                .maxIdleTime(Duration.ofHours(1))
                .maxLifeTime(Duration.ofHours(3))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .build();

        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .responseTimeout(Duration.ofMinutes(30))
                .keepAlive(true);

        return builder -> builder.clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
