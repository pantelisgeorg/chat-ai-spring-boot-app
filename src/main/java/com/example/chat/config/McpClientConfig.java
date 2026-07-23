package com.example.chat.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;

@Configuration
public class McpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);

    @Bean
    public ToolCallbackProvider mcpToolCallbacks(
            @Value("${spring.ai.mcp.client.streamable-http.connections.local.url:http://localhost:8765}") String serverUrl) {
        return new LazyMcpToolCallbackProvider(serverUrl);
    }

    private static class LazyMcpToolCallbackProvider implements ToolCallbackProvider {

        private final String serverUrl;
        private volatile ToolCallbackProvider delegate;

        LazyMcpToolCallbackProvider(String serverUrl) {
            this.serverUrl = serverUrl;
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            ToolCallbackProvider d = delegate;
            if (d != null) {
                return d.getToolCallbacks();
            }
            return connect().getToolCallbacks();
        }

        private synchronized ToolCallbackProvider connect() {
            if (delegate != null) return delegate;
            if (!isReachable()) {
                log.info("MCP server not reachable at {}, MCP tools unavailable", serverUrl);
                return ToolCallbackProvider.from();
            }
            try {
                var transport = HttpClientStreamableHttpTransport.builder(serverUrl)
                        .endpoint("/mcp")
                        .openConnectionOnStartup(false)
                        .build();
                McpSyncClient client = McpClient.sync(transport)
                        .clientInfo(new McpSchema.Implementation("app-mcp-client", "MCP Client", "1.0.0"))
                        .requestTimeout(Duration.ofSeconds(20))
                        .build();
                client.initialize();
                log.info("MCP server connected at {}, MCP tools enabled", serverUrl);
                delegate = new SyncMcpToolCallbackProvider(client);
                return delegate;
            } catch (Exception e) {
                log.warn("Failed to connect to MCP server at {}: {}", serverUrl, e.getMessage());
                return ToolCallbackProvider.from();
            }
        }

        private boolean isReachable() {
            try {
                var uri = URI.create(serverUrl + "/mcp").toURL();
                var conn = (HttpURLConnection) uri.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.connect();
                int code = conn.getResponseCode();
                conn.disconnect();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
