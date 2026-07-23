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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UnslothService {

    private static final Logger log = LoggerFactory.getLogger(UnslothService.class);

    // How long to wait for /v1/load to finish. GGUFs mmap fast; HF models can be slow.
    private static final Duration LOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration LOAD_POLL_INTERVAL = Duration.ofSeconds(2);

    private final String baseUrl;
    private final UnslothAuthManager auth;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, UnslothStatus> statusCache = new ConcurrentHashMap<>();

    public UnslothService(
            @Value("${unsloth.base-url:http://127.0.0.1:8888}") String baseUrl,
            UnslothAuthManager auth) {
        this.baseUrl = baseUrl;
        this.auth = auth;
    }

    /**
     * Snapshot of /v1/status, plus the flags we care about for routing / prompt shaping.
     */
    public record UnslothStatus(String activeModel, boolean isVision, boolean supportsTools,
                                boolean isGguf, List<String> loading, List<String> loaded) {}

    /**
     * List locally-discovered models. Hits /api/models/local (not /v1/models, which
     * only returns what's currently loaded) — so the dropdown shows every downloaded
     * model, just like the Studio UI does.
     */
    public List<String> listModels() {
        if (!auth.isConfigured()) {
            log.warn("Unsloth not configured — set 'unsloth.password' in application-local.properties or the UNSLOTH_PASSWORD env var");
            return List.of();
        }
        try {
            HttpResponse<String> resp = authedGet("/api/models/local");
            if (resp.statusCode() == 401) {
                auth.invalidate();
                resp = authedGet("/api/models/local");
            }
            if (resp.statusCode() / 100 != 2) {
                log.warn("Unsloth /api/models/local failed: status={} body={}", resp.statusCode(), resp.body());
                return List.of();
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode models = root.get("models");
            List<String> ids = new ArrayList<>();
            if (models != null && models.isArray()) {
                for (JsonNode m : models) {
                    String id = m.path("id").asText(null);
                    if (id != null && !id.isBlank()) ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                log.info("Unsloth has no local models — download one via the Studio UI first");
            } else {
                log.info("Unsloth local models: {}", ids);
            }
            return ids;
        } catch (java.net.ConnectException e) {
            log.warn("Cannot connect to Unsloth at {} — is Unsloth Studio running?", baseUrl);
            return List.of();
        } catch (Exception e) {
            log.warn("Unsloth listModels failed: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Query /v1/status. Returns null if not configured / unreachable / auth fails.
     */
    public UnslothStatus getStatus() {
        if (!auth.isConfigured()) return null;
        try {
            HttpResponse<String> resp = authedGet("/v1/status");
            if (resp.statusCode() == 401) {
                auth.invalidate();
                resp = authedGet("/v1/status");
            }
            if (resp.statusCode() / 100 != 2) {
                log.warn("Unsloth /v1/status failed: status={} body={}", resp.statusCode(), resp.body());
                return null;
            }
            return parseStatus(resp.body());
        } catch (Exception e) {
            log.warn("Unsloth getStatus failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Ensure the given model is the active loaded model in Unsloth. If a different
     * model is loaded, unload it first. Blocks until loading completes or times out.
     * Safe to call before every inference — cheap when the model is already active.
     */
    public boolean ensureLoaded(String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        if (!auth.isConfigured()) return false;

        UnslothStatus status = getStatus();
        if (status != null && modelId.equals(status.activeModel())
                && status.loading().isEmpty()) {
            return true;
        }

        // If a different model is active or still loading, clear it out first.
        if (status != null && status.activeModel() != null
                && !modelId.equals(status.activeModel())) {
            log.info("Unsloth: unloading {} to make room for {}", status.activeModel(), modelId);
            unloadAllModels();
        }

        log.info("Unsloth: loading model {} (may take a while)", modelId);
        try {
            String body = mapper.writeValueAsString(java.util.Map.of("model_path", modelId));
            HttpResponse<String> resp = authedPost("/v1/load", body, LOAD_TIMEOUT);
            if (resp.statusCode() == 401) {
                auth.invalidate();
                resp = authedPost("/v1/load", body, LOAD_TIMEOUT);
            }
            if (resp.statusCode() / 100 != 2) {
                log.warn("Unsloth /v1/load failed for {}: status={} body={}", modelId, resp.statusCode(), resp.body());
                return false;
            }
        } catch (Exception e) {
            log.warn("Unsloth /v1/load error for {}: {}", modelId, e.getMessage());
            return false;
        }

        return waitForLoad(modelId);
    }

    private boolean waitForLoad(String modelId) {
        long deadline = System.nanoTime() + LOAD_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            UnslothStatus s = getStatus();
            if (s != null && modelId.equals(s.activeModel()) && s.loading().isEmpty()) {
                statusCache.put(modelId, s);
                log.info("Unsloth: model {} is loaded (vision={}, tools={}, gguf={})",
                        modelId, s.isVision(), s.supportsTools(), s.isGguf());
                return true;
            }
            try {
                Thread.sleep(LOAD_POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Unsloth: model {} did not finish loading within {}", modelId, LOAD_TIMEOUT);
        return false;
    }

    public boolean supportsVision(String modelId) {
        return capability(modelId, UnslothStatus::isVision);
    }

    public boolean supportsTools(String modelId) {
        return capability(modelId, UnslothStatus::supportsTools);
    }

    private boolean capability(String modelId, java.util.function.Predicate<UnslothStatus> getter) {
        if (modelId == null) return false;
        UnslothStatus cached = statusCache.get(modelId);
        if (cached != null) return getter.test(cached);
        UnslothStatus now = getStatus();
        if (now != null && modelId.equals(now.activeModel())) {
            statusCache.put(modelId, now);
            return getter.test(now);
        }
        return false;
    }

    @PreDestroy
    public void onShutdown() {
        try {
            int n = unloadAllModels();
            if (n > 0) log.info("Shutdown: unloaded {} Unsloth model(s)", n);
        } catch (Exception e) {
            log.warn("Unsloth shutdown unload failed: {}", e.getMessage());
        }
    }

    /**
     * Ask Unsloth to unload the currently loaded model. Unsloth only keeps one
     * model resident at a time, so a single /v1/unload call is enough.
     * Returns 1 if a model was unloaded, 0 otherwise.
     */
    public int unloadAllModels() {
        if (!auth.isConfigured()) return 0;
        try {
            HttpResponse<String> resp = authedPost("/v1/unload", "{}", Duration.ofSeconds(30));
            if (resp.statusCode() == 401) {
                auth.invalidate();
                resp = authedPost("/v1/unload", "{}", Duration.ofSeconds(30));
            }
            statusCache.clear();
            if (resp.statusCode() / 100 == 2) {
                log.info("Unsloth model unloaded");
                return 1;
            }
            log.info("Unsloth unload returned status={} — likely no model loaded", resp.statusCode());
            return 0;
        } catch (Exception e) {
            log.warn("Unsloth unload failed: {}", e.getMessage());
            return 0;
        }
    }

    private UnslothStatus parseStatus(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        String active = root.path("active_model").isNull() ? null : root.path("active_model").asText(null);
        List<String> loading = new ArrayList<>();
        for (JsonNode n : root.withArray("loading")) loading.add(n.asText());
        List<String> loaded = new ArrayList<>();
        for (JsonNode n : root.withArray("loaded")) loaded.add(n.asText());
        return new UnslothStatus(
                active,
                root.path("is_vision").asBoolean(false),
                root.path("supports_tools").asBoolean(false),
                root.path("is_gguf").asBoolean(false),
                loading,
                loaded
        );
    }

    private HttpResponse<String> authedGet(String path) throws Exception {
        String token = auth.getAccessToken();
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> authedPost(String path, String body, Duration timeout) throws Exception {
        String token = auth.getAccessToken();
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
