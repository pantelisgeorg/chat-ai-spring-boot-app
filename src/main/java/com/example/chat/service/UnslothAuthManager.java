package com.example.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

@Component
public class UnslothAuthManager {

    private static final Logger log = LoggerFactory.getLogger(UnslothAuthManager.class);

    // Refresh the access token slightly before its JWT `exp` claim, so concurrent
    // requests never see an expired token mid-stream.
    private static final long EXPIRY_SKEW_SECONDS = 30;

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long accessExpiresAtEpoch;

    public UnslothAuthManager(
            @Value("${unsloth.base-url:http://127.0.0.1:8888}") String baseUrl,
            @Value("${unsloth.username:unsloth}") String username,
            @Value("${unsloth.password:}") String password) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    public boolean isConfigured() {
        return password != null && !password.isBlank() && !"CHANGE_ME".equals(password);
    }

    /**
     * Return a valid access token, refreshing or re-logging-in as needed.
     * Returns null when auth is not configured (so callers can skip Unsloth cleanly).
     */
    public synchronized String getAccessToken() {
        if (!isConfigured()) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000;
        if (accessToken != null && now < accessExpiresAtEpoch - EXPIRY_SKEW_SECONDS) {
            return accessToken;
        }
        if (refreshToken != null && tryRefresh()) {
            return accessToken;
        }
        if (tryLogin()) {
            return accessToken;
        }
        return null;
    }

    /**
     * Force a re-login — call this if an API returns 401 despite our cached token.
     */
    public synchronized void invalidate() {
        accessToken = null;
        accessExpiresAtEpoch = 0;
    }

    private boolean tryLogin() {
        try {
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "username", username,
                    "password", password
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Unsloth login failed: status={} body={}", resp.statusCode(), resp.body());
                return false;
            }
            return storeTokens(resp.body());
        } catch (Exception e) {
            log.warn("Unsloth login error: {}", e.getMessage());
            return false;
        }
    }

    private boolean tryRefresh() {
        try {
            String body = mapper.writeValueAsString(java.util.Map.of("refresh_token", refreshToken));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/auth/refresh"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.info("Unsloth refresh failed (status={}); will re-login", resp.statusCode());
                return false;
            }
            return storeTokens(resp.body());
        } catch (Exception e) {
            log.info("Unsloth refresh error ({}); will re-login", e.getMessage());
            return false;
        }
    }

    private boolean storeTokens(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        String access = root.path("access_token").asText(null);
        String refresh = root.path("refresh_token").asText(null);
        if (access == null) {
            log.warn("Unsloth auth response missing access_token");
            return false;
        }
        this.accessToken = access;
        this.refreshToken = refresh;
        this.accessExpiresAtEpoch = parseJwtExp(access);
        long ttl = accessExpiresAtEpoch - System.currentTimeMillis() / 1000;
        log.info("Unsloth auth OK — access token valid for {}s", Math.max(0, ttl));
        return true;
    }

    // Decode the JWT payload's `exp` claim without verification. We only use it
    // to decide when to refresh proactively; the server is the authority on validity.
    private long parseJwtExp(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            byte[] payload = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode node = mapper.readTree(payload);
            long exp = node.path("exp").asLong(0);
            if (exp == 0) {
                // No exp claim — assume a conservative 15 min lifetime.
                return System.currentTimeMillis() / 1000 + 900;
            }
            return exp;
        } catch (Exception e) {
            log.debug("Could not parse JWT exp: {}", e.getMessage());
            return System.currentTimeMillis() / 1000 + 900;
        }
    }

    private static String padBase64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return pad == 0 ? s : s + "====".substring(0, pad);
    }
}
