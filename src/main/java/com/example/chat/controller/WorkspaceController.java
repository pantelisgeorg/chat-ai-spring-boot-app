package com.example.chat.controller;

import com.example.chat.tools.FileTools;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/workspace")
public class WorkspaceController {

    private final FileTools fileTools;

    public WorkspaceController(FileTools fileTools) {
        this.fileTools = fileTools;
    }

    @GetMapping("/files")
    public List<Map<String, Object>> listFiles(@RequestParam(defaultValue = "") String path) throws IOException {
        Path root = fileTools.getWorkspaceRoot();
        Path target = path.isBlank() ? root : root.resolve(path).normalize();

        if (!target.startsWith(root) || !Files.exists(target) || !Files.isDirectory(target)) {
            return List.of();
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(target)) {
            children.sorted((a, b) -> {
                // Directories first, then alphabetical
                boolean aDir = Files.isDirectory(a);
                boolean bDir = Files.isDirectory(b);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).forEach(p -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", p.getFileName().toString());
                entry.put("path", root.relativize(p).toString());
                entry.put("directory", Files.isDirectory(p));
                try {
                    if (!Files.isDirectory(p)) {
                        entry.put("size", Files.size(p));
                    }
                } catch (IOException ignored) {}
                entries.add(entry);
            });
        }
        return entries;
    }

    @GetMapping("/read")
    public ResponseEntity<String> readFile(@RequestParam String path) throws IOException {
        Path root = fileTools.getWorkspaceRoot();
        Path target = root.resolve(path).normalize();

        if (!target.startsWith(root) || !Files.exists(target) || Files.isDirectory(target)) {
            return ResponseEntity.notFound().build();
        }

        String content = Files.readString(target);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(content);
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(@RequestParam String path) throws IOException {
        Path root = fileTools.getWorkspaceRoot();
        Path target = root.resolve(path).normalize();

        if (!target.startsWith(root) || !Files.exists(target) || Files.isDirectory(target)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(target);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + target.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @DeleteMapping("/files")
    public ResponseEntity<String> deleteFile(@RequestParam String path) throws IOException {
        Path root = fileTools.getWorkspaceRoot();
        Path target = root.resolve(path).normalize();

        if (!target.startsWith(root) || !Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }

        if (Files.isDirectory(target)) {
            // Delete directory recursively
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
        } else {
            Files.delete(target);
        }
        return ResponseEntity.ok("Deleted: " + path);
    }
}
