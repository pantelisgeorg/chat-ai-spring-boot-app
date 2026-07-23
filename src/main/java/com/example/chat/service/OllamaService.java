package com.example.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, List<String>> capabilitiesCache = new ConcurrentHashMap<>();

    public OllamaService(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public List<String> listModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode models = root.get("models");

            List<String> modelNames = new ArrayList<>();
            if (models != null && models.isArray()) {
                for (JsonNode model : models) {
                    modelNames.add(model.get("name").asText());
                }
            }
            return modelNames;
        } catch (Exception e) {
            log.error("Failed to list Ollama models from {}: {}", baseUrl, e.getMessage(), e);
            return List.of();
        }
    }

    public String pullModel(String modelName) {
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("name", modelName));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/pull"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Ollama pull returned {}: {}", response.statusCode(), response.body());
                return "Error: " + response.body();
            }
            return "Pulling " + modelName + "...";
        } catch (Exception e) {
            log.error("Failed to pull model {}: {}", modelName, e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the capabilities list reported by Ollama for a given model
     * (e.g. ["completion", "vision", "tools"]). Cached after first successful fetch.
     * Returns an empty list on failure — callers treating an unknown capability
     * as absent is the safe default (avoids sending images to text-only models).
     */
    public List<String> getCapabilities(String modelName) {
        if (modelName == null || modelName.isBlank()) return List.of();
        List<String> cached = capabilitiesCache.get(modelName);
        if (cached != null) return cached;

        try {
            String body = objectMapper.writeValueAsString(Map.of("name", modelName));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/show"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Ollama /api/show returned {} for model {}", response.statusCode(), modelName);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode caps = root.get("capabilities");
            List<String> result = new ArrayList<>();
            if (caps != null && caps.isArray()) {
                for (JsonNode c : caps) result.add(c.asText());
            }
            capabilitiesCache.put(modelName, List.copyOf(result));
            return result;
        } catch (Exception e) {
            log.warn("Could not fetch capabilities for {}: {}", modelName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the names of models currently loaded in Ollama's memory.
     */
    public List<String> getRunningModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ps"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode models = root.get("models");

            List<String> running = new ArrayList<>();
            if (models != null && models.isArray()) {
                for (JsonNode model : models) {
                    running.add(model.get("name").asText());
                }
            }
            return running;
        } catch (Exception e) {
            log.warn("Could not query running Ollama models: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Unload all currently loaded models from Ollama's memory.
     * Uses keep_alive=0 to tell Ollama to release the model immediately.
     * Returns the number of models unloaded.
     */
    @PreDestroy
    public void onShutdown() {
        // Ollama runs as a separate process and keeps models resident per its keep_alive TTL.
        // Unload explicitly on app shutdown so Ctrl+C / container stop frees RAM immediately.
        try {
            int n = unloadAllModels();
            if (n > 0) log.info("Shutdown: unloaded {} Ollama model(s)", n);
        } catch (Exception e) {
            log.warn("Shutdown unload failed: {}", e.getMessage());
        }
    }

    public int unloadAllModels() {
        List<String> running = getRunningModels();
        if (running.isEmpty()) {
            log.info("No Ollama models currently loaded in memory");
            return 0;
        }

        int unloaded = 0;
        for (String modelName : running) {
            try {
                String body = objectMapper.writeValueAsString(
                        Map.of("model", modelName, "keep_alive", 0));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/generate"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                unloaded++;
                log.info("Unloaded Ollama model from memory: {}", modelName);
            } catch (Exception e) {
                log.warn("Failed to unload model {}: {}", modelName, e.getMessage());
            }
        }
        return unloaded;
    }
}
