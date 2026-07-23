package com.example.chat.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FileTools {

    private static final Logger log = LoggerFactory.getLogger(FileTools.class);

    // Tracks how many files were created via native tool calls (per request)
    private final AtomicInteger nativeToolCallCount = new AtomicInteger(0);

    // Pattern 1 (primary): File path on line before code block.
    // Handles: **`path`**, `path`, **path**, ### path, 1. `path`, File: path, etc.
    // Also allows an optional blank line between the path and code fence.
    private static final Pattern FILE_BLOCK_PATTERN = Pattern.compile(
            "(?:^|\\n)" +
            "\\s*(?:\\d+\\.\\s+)?" +                            // optional "1. "
            "(?:[*#]+\\s*)?(?:`{1,3})?" +                       // optional bold/header/backtick
            "(?:File:\\s*|Create\\s+|Filename:\\s*)?" +          // optional prefix
            "([\\w./][\\w.\\-/]*\\.[a-zA-Z]{1,10})" +          // file path (allow ./ prefix)
            "(?:`{1,3})?(?:[*]*)?:?\\s*\\n" +                  // close formatting
            "(?:\\s*\\n)?" +                                     // optional blank line
            "```[a-zA-Z]*\\n" +                                 // code fence
            "(.*?)" +                                            // content
            "\\n```",
            Pattern.DOTALL | Pattern.MULTILINE
    );

    // Pattern 2: <create-file> XML tags
    private static final Pattern CREATE_FILE_PATTERN = Pattern.compile(
            "<create-file\\s+path=\"([^\"]+)\"\\s*>(.*?)</create-file>",
            Pattern.DOTALL
    );

    // Pattern 3: language:path in code fence, e.g. ```java:src/App.java
    private static final Pattern LANG_PATH_PATTERN = Pattern.compile(
            "```[a-zA-Z]+:([\\w./][\\w.\\-/]*\\.[a-zA-Z]{1,10})\\s*\\n" +
            "(.*?)" +
            "\\n```",
            Pattern.DOTALL
    );

    // Pattern 4: First-line comment in code block contains a file path.
    // Matches: // src/main/java/App.java or # config/settings.yaml as first line of a code block.
    // Requires at least one "/" in the path to reduce false positives.
    private static final Pattern COMMENT_PATH_PATTERN = Pattern.compile(
            "```[a-zA-Z]*\\n" +
            "\\s*(?://|#|--|/\\*\\*?|<!--)" +                                 // comment marker
            "\\s*([\\w./][\\w.\\-/]*/[\\w.\\-]+\\.[a-zA-Z]{1,10})" +        // path (must have /)
            "(?:\\s*\\*+/|\\s*-->)?" +                                        // optional close
            "\\s*\\n" +                                                        // end of line
            "(.*?)" +                                                          // content after comment
            "\\n```",
            Pattern.DOTALL
    );

    private final Path workspaceRoot;

    public FileTools(@Value("${workspace.root:./workspace}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.workspaceRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspace directory: " + this.workspaceRoot, e);
        }
    }

    // ===== Native tool calling methods (for models that support it) =====

    @Tool(description = "Create a directory (and any parent directories) at the given path inside the workspace. Use forward slashes for path separators. Example: 'src/main/java'")
    public String createFolder(@ToolParam(description = "Relative path of the directory to create") String path) {
        try {
            Path target = resolveAndValidate(path);
            Files.createDirectories(target);
            nativeToolCallCount.incrementAndGet();
            log.info("[Native Tool] Created directory: {}", path);
            return "Created directory: " + workspaceRoot.relativize(target);
        } catch (Exception e) {
            return "Error creating directory: " + e.getMessage();
        }
    }

    @Tool(description = "Write content to a file inside the workspace. Parent directories are created automatically. Use forward slashes. Example path: 'src/Main.java'")
    public String writeFile(
            @ToolParam(description = "Relative file path, e.g. 'src/Main.java'") String path,
            @ToolParam(description = "The full content to write to the file") String content) {
        try {
            Path target = resolveAndValidate(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            nativeToolCallCount.incrementAndGet();
            log.info("[Native Tool] Written: {} ({} chars)", path, content.length());
            return "Written file: " + workspaceRoot.relativize(target) + " (" + content.length() + " chars)";
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool(description = "Read the content of a file inside the workspace.")
    public String readFile(@ToolParam(description = "Relative file path to read") String path) {
        try {
            Path target = resolveAndValidate(path);
            if (!Files.exists(target)) {
                return "File not found: " + path;
            }
            return Files.readString(target);
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool(description = "List all files and directories inside the workspace or a subdirectory.")
    public String listFiles(@ToolParam(description = "Relative directory path, or empty for root") String path) {
        try {
            Path target = (path == null || path.isBlank()) ? workspaceRoot : resolveAndValidate(path);
            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return "Directory not found: " + path;
            }
            try (var walk = Files.walk(target, 10)) {
                String listing = walk
                        .map(p -> {
                            String rel = workspaceRoot.relativize(p).toString();
                            if (rel.isEmpty()) return "./ (workspace root)";
                            String indent = "  ".repeat(workspaceRoot.relativize(p).getNameCount() - 1);
                            String name = p.getFileName().toString();
                            return indent + (Files.isDirectory(p) ? name + "/" : name);
                        })
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
                return listing.isEmpty() ? "(empty directory)" : listing;
            }
        } catch (Exception e) {
            return "Error listing files: " + e.getMessage();
        }
    }

    // ===== Prompt-parsing fallback (for models that don't support native tools) =====

    /**
     * Reset the native tool call counter. Call before each request.
     */
    public void resetToolCallCount() {
        nativeToolCallCount.set(0);
    }

    /**
     * Returns how many files were created via native @Tool calls in this request.
     */
    public int getNativeToolCallCount() {
        return nativeToolCallCount.get();
    }

    /**
     * Parse file blocks from model output and write them to the workspace.
     * Tries multiple patterns in order to handle the many formatting styles models use.
     * Deduplicates by file path so the same file isn't written twice.
     * Returns the list of file paths created.
     */
    public List<String> parseAndCreateFiles(String modelResponse) {
        List<String> createdFiles = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        // 1. Primary: file path line before code block (most common model output format)
        Matcher matcher = FILE_BLOCK_PATTERN.matcher(modelResponse);
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String content = matcher.group(2);
            if (!seenPaths.contains(path) && writeFileToWorkspace(path, content)) {
                createdFiles.add(path);
                seenPaths.add(path);
            }
        }

        // 2. language:path in code fence (e.g. ```java:src/App.java)
        matcher = LANG_PATH_PATTERN.matcher(modelResponse);
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String content = matcher.group(2);
            if (!seenPaths.contains(path) && writeFileToWorkspace(path, content)) {
                createdFiles.add(path);
                seenPaths.add(path);
            }
        }

        // 3. First-line comment contains a file path (e.g. // src/main/java/App.java)
        matcher = COMMENT_PATH_PATTERN.matcher(modelResponse);
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String content = matcher.group(2);
            if (!seenPaths.contains(path) && writeFileToWorkspace(path, content)) {
                createdFiles.add(path);
                seenPaths.add(path);
            }
        }

        // 4. Fallback: <create-file> XML tags
        if (createdFiles.isEmpty()) {
            matcher = CREATE_FILE_PATTERN.matcher(modelResponse);
            while (matcher.find()) {
                String path = matcher.group(1).trim();
                String content = matcher.group(2);
                if (content.startsWith("\n")) content = content.substring(1);
                if (content.endsWith("\n")) content = content.substring(0, content.length() - 1);
                if (!seenPaths.contains(path) && writeFileToWorkspace(path, content)) {
                    createdFiles.add(path);
                    seenPaths.add(path);
                }
            }
        }

        if (!createdFiles.isEmpty()) {
            log.info("[Prompt-Parse] Created {} files: {}", createdFiles.size(), createdFiles);
        }

        return createdFiles;
    }

    private boolean writeFileToWorkspace(String path, String content) {
        try {
            Path target = resolveAndValidate(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return true;
        } catch (Exception e) {
            log.error("Failed to write {}: {}", path, e.getMessage());
            return false;
        }
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    private Path resolveAndValidate(String relativePath) {
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        Path resolved = workspaceRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new SecurityException("Path traversal denied: path must stay within workspace");
        }
        return resolved;
    }
}
