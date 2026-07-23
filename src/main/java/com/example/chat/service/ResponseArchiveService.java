package com.example.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Guarantees that every model response is saved as a file in the workspace,
 * regardless of whether native tool calling or prompt parsing succeeds.
 * This is the safety net — the raw response is always on disk.
 */
@Service
public class ResponseArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ResponseArchiveService.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path archiveRoot;

    public ResponseArchiveService(@Value("${workspace.root:./workspace}") String workspaceRoot) {
        this.archiveRoot = Path.of(workspaceRoot).toAbsolutePath().normalize().resolve(".archive");
        try {
            Files.createDirectories(archiveRoot);
        } catch (IOException e) {
            log.error("Failed to create archive directory: {}", archiveRoot, e);
        }
    }

    /**
     * Archive a model response to disk. Always succeeds or logs the failure.
     * Returns the path of the saved file, or null on failure.
     */
    public Path archiveResponse(Long conversationId, String model, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            Path conversationDir = archiveRoot.resolve("conversation-" + conversationId);
            Files.createDirectories(conversationDir);

            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            String safeModel = model.replaceAll("[^a-zA-Z0-9._-]", "_");
            String filename = timestamp + "_" + safeModel + ".md";

            Path archiveFile = conversationDir.resolve(filename);

            StringBuilder archived = new StringBuilder();
            archived.append("<!-- Model: ").append(model).append(" -->\n");
            archived.append("<!-- Timestamp: ").append(LocalDateTime.now()).append(" -->\n");
            archived.append("<!-- Conversation: ").append(conversationId).append(" -->\n\n");
            archived.append(content);

            Files.writeString(archiveFile, archived.toString());

            log.info("Archived response ({} chars) to: {}", content.length(), archiveFile);
            return archiveFile;
        } catch (IOException e) {
            log.error("Failed to archive response for conversation {}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    public Path getArchiveRoot() {
        return archiveRoot;
    }
}
