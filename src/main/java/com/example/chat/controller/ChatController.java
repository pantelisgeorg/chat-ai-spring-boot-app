package com.example.chat.controller;

import com.example.chat.model.ChatMessage;
import com.example.chat.model.Conversation;
import com.example.chat.service.ChatService;
import com.example.chat.service.LmStudioService;
import com.example.chat.service.OllamaService;
import com.example.chat.service.ResponseArchiveService;
import com.example.chat.service.UnslothService;
import com.example.chat.tools.FileTools;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatService chatService;
    private final OllamaService ollamaService;
    private final LmStudioService lmStudioService;
    private final UnslothService unslothService;
    private final FileTools fileTools;
    private final ResponseArchiveService archiveService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${chat.default-num-ctx:8000}")
    private int defaultNumCtx;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public ChatController(ChatService chatService, OllamaService ollamaService,
                          LmStudioService lmStudioService, UnslothService unslothService,
                          FileTools fileTools, ResponseArchiveService archiveService) {
        this.chatService = chatService;
        this.ollamaService = ollamaService;
        this.lmStudioService = lmStudioService;
        this.unslothService = unslothService;
        this.fileTools = fileTools;
        this.archiveService = archiveService;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<Conversation> conversations = chatService.getAllConversations();
        List<String> availableModels = ollamaService.listModels();
        model.addAttribute("conversations", conversations);
        model.addAttribute("models", availableModels);
        model.addAttribute("defaultModel", availableModels.isEmpty() ? "mistral:latest" : availableModels.get(0));
        model.addAttribute("defaultProvider", "ollama");
        model.addAttribute("defaultNumCtx", defaultNumCtx);
        return "index";
    }

    @PostMapping("/conversations")
    public String createConversation(@RequestParam String model,
                                     @RequestParam(defaultValue = "ollama") String provider) {
        Conversation conversation = chatService.createConversation(model, provider);
        return "redirect:/chat/" + conversation.getId();
    }

    @GetMapping("/chat/{id}")
    public String chat(@PathVariable Long id, Model model) {
        Conversation conversation = chatService.getConversation(id);
        if (conversation == null) {
            return "redirect:/";
        }
        String provider = conversation.getProvider();
        List<String> availableModels;
        if ("lmstudio".equals(provider)) {
            availableModels = lmStudioService.listModels();
        } else if ("unsloth".equals(provider)) {
            availableModels = unslothService.listModels();
        } else {
            availableModels = ollamaService.listModels();
        }
        List<Conversation> conversations = chatService.getAllConversations();
        model.addAttribute("conversation", conversation);
        model.addAttribute("conversations", conversations);
        model.addAttribute("models", availableModels);
        model.addAttribute("messages", conversation.getMessages());
        model.addAttribute("defaultProvider", provider);
        model.addAttribute("defaultNumCtx", defaultNumCtx);
        return "index";
    }

    @GetMapping("/api/models")
    @ResponseBody
    public List<String> getModelsByProvider(@RequestParam(defaultValue = "ollama") String provider) {
        if ("lmstudio".equals(provider)) {
            return lmStudioService.listModels();
        }
        if ("unsloth".equals(provider)) {
            return unslothService.listModels();
        }
        return ollamaService.listModels();
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseBody
    public String deleteConversation(@PathVariable Long id) {
        chatService.deleteConversation(id);
        return "";
    }

    @GetMapping("/conversations/sidebar")
    public String sidebar(Model model) {
        model.addAttribute("conversations", chatService.getAllConversations());
        return "fragments/sidebar :: conversationList";
    }

    @PostMapping("/chat/{id}/send")
    @ResponseBody
    public String sendMessage(@PathVariable Long id,
                              @RequestParam String message,
                              @RequestParam(required = false) String imageData,
                              @RequestParam(required = false) String imageMimeType) {
        chatService.saveUserMessage(id, message, imageData, imageMimeType);
        return "ok";
    }

    @PostMapping("/chat/{id}/save-assistant")
    @ResponseBody
    public String saveAssistantMessage(@PathVariable Long id, @RequestParam String message) {
        chatService.saveAssistantMessage(id, message);
        return "ok";
    }

    @GetMapping(path = "/chat/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamResponse(
            @PathVariable Long id,
            @RequestParam String model,
            @RequestParam(defaultValue = "ollama") String provider,
            @RequestParam(defaultValue = "false") boolean useTools,
            @RequestParam(required = false) Integer numCtx) {

        int effectiveNumCtx = (numCtx != null && numCtx > 0) ? numCtx : defaultNumCtx;

        // 3 h: 31B+ reasoning models on a swap-bound host have hit the prior 60-min
        // budget mid-generation (8-min TTFT + long tail). SSE timeout is the wall-clock
        // budget for the whole turn, not just the wait for tokens. Finite so a stuck
        // upstream still releases the worker thread eventually.
        SseEmitter emitter = new SseEmitter(10_800_000L);

        AtomicReference<reactor.core.Disposable> subRef = new AtomicReference<>();
        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
        AtomicReference<com.example.chat.service.ChatService.StreamChunk.Usage> lastUsage = new AtomicReference<>();

        // Heartbeat keeps the SSE connection (and any proxies) alive while the model
        // is still processing the prompt and hasn't emitted a token yet. SSE comments
        // (lines starting with ':') are ignored by EventSource but reset idle timers.
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (firstChunkReceived.get() || clientGone.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                clientGone.set(true);
            }
        }, 15, 15, TimeUnit.SECONDS);

        Runnable disposeSub = () -> {
            heartbeat.cancel(false);
            reactor.core.Disposable s = subRef.get();
            if (s != null && !s.isDisposed()) s.dispose();
        };
        emitter.onCompletion(disposeSub);
        emitter.onTimeout(() -> { disposeSub.run(); emitter.complete(); });
        emitter.onError(err -> disposeSub.run());

        executor.execute(() -> {
            StringBuilder fullResponse = new StringBuilder();
            reactor.core.Disposable sub = chatService.streamResponse(id, model, provider, useTools, effectiveNumCtx)
                    .doOnNext(chunk -> {
                        firstChunkReceived.set(true);
                        if (clientGone.get()) return;
                        if (com.example.chat.service.ChatService.StreamChunk.USAGE.equals(chunk.kind())) {
                            lastUsage.set(chunk.usage());
                            return;
                        }
                        boolean isThinking = com.example.chat.service.ChatService.StreamChunk.THINKING.equals(chunk.kind());
                        // Only content goes into the persisted/archived response. Thinking is shown
                        // live in a collapsible UI block and discarded after the turn.
                        if (!isThinking) {
                            fullResponse.append(chunk.text());
                        }
                        try {
                            String jsonChunk = objectMapper.writeValueAsString(java.util.Map.of("t", chunk.text()));
                            emitter.send(SseEmitter.event()
                                    .name(isThinking ? "thinking" : "chunk")
                                    .data(jsonChunk, MediaType.TEXT_PLAIN));
                        } catch (Exception e) {
                            // Client disconnected (Broken pipe / AsyncRequestNotUsable).
                            // Save partial, cancel upstream, stop generating for nobody.
                            clientGone.set(true);
                            log.info("Client disconnected mid-stream for conversation {} — saving partial and cancelling", id);
                            savePartial(id, model, fullResponse.toString(), lastUsage.get());
                            disposeSub.run();
                        }
                    })
                    .doOnComplete(() -> {
                        if (clientGone.get()) return;
                        String response = fullResponse.toString();
                        if (useTools) {
                            int nativeCalls = fileTools.getNativeToolCallCount();
                            if (nativeCalls > 0) {
                                log.info("Native tool calling succeeded: {} tool calls", nativeCalls);
                            } else {
                                var created = fileTools.parseAndCreateFiles(response);
                                if (!created.isEmpty()) {
                                    log.info("Prompt-parsing created {} files: {}", created.size(), created);
                                }
                            }
                        }
                        var usage = lastUsage.get();
                        try {
                            archiveService.archiveResponse(id, model, response);
                            chatService.saveAssistantMessage(id, response, usage);
                        } catch (Exception e) {
                            log.warn("Failed to save assistant message for {}: {}", id, e.getMessage());
                        }
                        try {
                            if (usage != null) {
                                emitter.send(SseEmitter.event().name("usage").data(
                                        objectMapper.writeValueAsString(java.util.Map.of(
                                                "inputTokens", usage.inputTokens() != null ? usage.inputTokens() : 0,
                                                "outputTokens", usage.outputTokens() != null ? usage.outputTokens() : 0,
                                                "durationMs", usage.durationMs() != null ? usage.durationMs() : 0
                                        )), MediaType.APPLICATION_JSON));
                            }
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        } catch (Exception ignored) {
                            // Client gone at the last moment — nothing to do.
                        }
                        try { emitter.complete(); } catch (Exception ignored) {}
                    })
                    .subscribe(
                            chunk -> {},
                            err -> {
                                log.warn("Stream error for conversation {}: {} - {}",
                                        id, err.getClass().getSimpleName(), err.getMessage());
                                savePartial(id, model, fullResponse.toString(), lastUsage.get());
                                if (!clientGone.get()) {
                                    try {
                                        String payload = objectMapper.writeValueAsString(Map.of(
                                                "type", err.getClass().getSimpleName(),
                                                "message", err.getMessage() != null ? err.getMessage() : "Unknown error",
                                                "hadOutput", fullResponse.length() > 0));
                                        emitter.send(SseEmitter.event()
                                                .name("error")
                                                .data(payload, MediaType.APPLICATION_JSON));
                                    } catch (Exception ignored) {}
                                }
                                try { emitter.complete(); } catch (Exception ignored) {}
                            }
                    );
            subRef.set(sub);
        });

        return emitter;
    }

    private void savePartial(Long id, String model, String partial,
                             com.example.chat.service.ChatService.StreamChunk.Usage usage) {
        if (partial == null || partial.isEmpty()) return;
        try {
            archiveService.archiveResponse(id, model, partial);
            chatService.saveAssistantMessage(id, partial, usage);
            log.info("Saved partial response ({} chars) for conversation {}", partial.length(), id);
        } catch (Exception e) {
            log.warn("Failed to save partial for {}: {}", id, e.getMessage());
        }
    }

    @GetMapping("/chat/{id}/usage")
    @ResponseBody
    public Map<String, Object> getUsage(@PathVariable Long id) {
        Conversation conversation = chatService.getConversation(id);
        if (conversation == null) {
            return Map.of("totalInput", 0, "totalOutput", 0, "count", 0);
        }
        int totalInput = 0, totalOutput = 0, count = 0;
        for (ChatMessage msg : conversation.getMessages()) {
            if (msg.getRole() == ChatMessage.Role.ASSISTANT) {
                if (msg.getInputTokens() != null) totalInput += msg.getInputTokens();
                if (msg.getOutputTokens() != null) totalOutput += msg.getOutputTokens();
                count++;
            }
        }
        return Map.of("totalInput", totalInput, "totalOutput", totalOutput, "count", count,
                "total", totalInput + totalOutput);
    }

    /**
     * Manually extract files from all assistant messages in a conversation.
     * This is the last-resort fallback — the user can trigger this at any time
     * to re-run file extraction with the enhanced patterns.
     */
    @PostMapping("/chat/{id}/extract-files")
    @ResponseBody
    public Map<String, Object> extractFiles(@PathVariable Long id) {
        Conversation conversation = chatService.getConversation(id);
        if (conversation == null) {
            return Map.of("files", List.of(), "count", 0, "error", "Conversation not found");
        }
        List<String> allExtracted = new ArrayList<>();
        for (ChatMessage msg : conversation.getMessages()) {
            if (msg.getRole() == ChatMessage.Role.ASSISTANT) {
                var created = fileTools.parseAndCreateFiles(msg.getContent());
                allExtracted.addAll(created);
            }
        }
        log.info("Manual extract for conversation {}: {} files extracted", id, allExtracted.size());
        return Map.of("files", allExtracted, "count", allExtracted.size());
    }
}
