package com.example.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class LmStudioService {

    private static final Logger log = LoggerFactory.getLogger(LmStudioService.class);
    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LmStudioService(@Value("${spring.ai.openai.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.replace("localhost", "127.0.0.1");
        log.info("LmStudioService initialized with base URL: {}", this.baseUrl);
    }

    public List<String> listModels() {
        log.info("Fetching LM Studio models from: {}/v1/models", baseUrl);
        try {
            HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(baseUrl + "/v1/models").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int status = conn.getResponseCode();
            log.info("LM Studio response status: {}", status);

            String body = readResponse(conn);
            conn.disconnect();

            log.info("LM Studio /v1/models raw response: {}", body);

            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");

            List<String> modelIds = new ArrayList<>();
            if (data != null && data.isArray()) {
                for (JsonNode model : data) {
                    String id = model.has("id") ? model.get("id").asText() : null;
                    if (id != null && !id.isBlank()) {
                        modelIds.add(id);
                    } else {
                        log.warn("LM Studio model entry missing 'id' field: {}", model);
                    }
                }
            } else {
                log.warn("LM Studio response has no 'data' array. Full response: {}", body);
            }

            if (modelIds.isEmpty()) {
                log.warn("No LM Studio models found. Is the LM Studio local server started? (Developer tab > Start Server)");
            } else {
                log.info("Found {} LM Studio model(s): {}", modelIds.size(), modelIds);
            }
            return modelIds;
        } catch (java.net.ConnectException e) {
            log.error("Cannot connect to LM Studio at {} — is the local server started? (Developer tab > Start Server)", baseUrl);
            return List.of();
        } catch (Exception e) {
            log.error("Failed to list LM Studio models: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean isAvailable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(baseUrl + "/v1/models").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int status = conn.getResponseCode();
            conn.disconnect();
            return status == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public void onShutdown() {
        try {
            int n = unloadAllModels();
            if (n > 0) log.info("Shutdown: unloaded {} LM Studio model(s)", n);
        } catch (Exception e) {
            log.warn("Shutdown unload failed: {}", e.getMessage());
        }
    }

    /**
     * Attempt to unload all models from LM Studio to free memory.
     * Tries multiple API endpoints since LM Studio versions vary.
     * Returns the number of models unloaded, or 0 if unload is not supported.
     */
    public int unloadAllModels() {
        List<String> loaded = listModels();
        if (loaded.isEmpty()) {
            return 0;
        }

        int unloaded = 0;
        for (String modelId : loaded) {
            if (tryUnloadModel(modelId)) {
                unloaded++;
                log.info("Unloaded LM Studio model from memory: {}", modelId);
            }
        }

        if (unloaded == 0 && !loaded.isEmpty()) {
            log.info("LM Studio API does not support remote unload — unload manually in the LM Studio UI to free memory");
        }
        return unloaded;
    }

    private boolean tryUnloadModel(String modelId) {
        // Try LM Studio developer API (0.3+)
        if (tryPost(baseUrl + "/api/v0/models/unload", "{\"model\":\"" + modelId + "\"}")) return true;
        // Try OpenAI-style DELETE
        if (tryDelete(baseUrl + "/v1/models/" + modelId)) return true;
        return false;
    }

    private boolean tryPost(String url, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = conn.getResponseCode();
            conn.disconnect();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryDelete(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int status = conn.getResponseCode();
            conn.disconnect();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
