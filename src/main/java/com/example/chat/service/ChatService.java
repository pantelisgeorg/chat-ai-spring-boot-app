package com.example.chat.service;

import com.example.chat.model.ChatMessage;
import com.example.chat.model.Conversation;
import com.example.chat.repository.ConversationRepository;
import com.example.chat.tools.FileTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ChatService {

    public record StreamChunk(String kind, String text, Usage usage) {
        public static final String CONTENT = "content";
        public static final String THINKING = "thinking";
        public static final String USAGE = "usage";

        public StreamChunk(String kind, String text) {
            this(kind, text, null);
        }

        public record Usage(Integer inputTokens, Integer outputTokens, Long durationMs) {}
    }

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant. Always format code using proper Markdown code blocks with triple backticks and language identifier. " +
            "IMPORTANT: Never repeat yourself. Once you have written code or an explanation, do not restate it. Be concise. " +
            "When writing code, ALWAYS provide complete, runnable code. For Java: include the public class declaration, all necessary imports, and a main method with example usage. " +
            "Never write partial snippets or pseudocode unless explicitly asked. The user should be able to copy your code, save it to a file, and run it immediately.";

    private static final String TOOL_SYSTEM_PROMPT =
            "You are a helpful AI assistant that creates project files in a workspace.\n\n" +
            "When the user asks you to create a project, implement code, or generate files, " +
            "output each file as a markdown code block with the FULL FILE PATH on the line immediately before it.\n\n" +
            "You MUST follow this EXACT format for every file:\n\n" +
            "**`path/to/file.ext`**\n" +
            "```language\n" +
            "file content here\n" +
            "```\n\n" +
            "Example:\n\n" +
            "**`src/main/java/com/example/App.java`**\n" +
            "```java\n" +
            "package com.example;\n\n" +
            "public class App {\n" +
            "    public static void main(String[] args) {\n" +
            "        System.out.println(\"Hello World\");\n" +
            "    }\n" +
            "}\n" +
            "```\n\n" +
            "Rules:\n" +
            "- ALWAYS put the file path in bold backticks on the line before the code block: **`path/to/file`**\n" +
            "- The path must be a relative file path (e.g. src/Main.java, pom.xml)\n" +
            "- Include COMPLETE file content — no placeholders, no '...', no truncation\n" +
            "- You can create multiple files in one response\n" +
            "- Be concise. Do not repeat yourself.";

    private static final String NATIVE_TOOL_SYSTEM_PROMPT =
            "You are a helpful AI assistant. You can answer questions, describe images, explain code, " +
            "and have normal conversations. You also have access to file-system tools for creating project files.\n\n" +
            "Available tools (use ONLY when the user is asking you to create or modify files):\n" +
            "- writeFile(path, content) — write a file inside the workspace\n" +
            "- createFolder(path) — create a directory (parents auto-created)\n" +
            "- readFile(path) — read an existing workspace file\n" +
            "- listFiles(path) — list files in the workspace or a subdirectory\n\n" +
            "Rules:\n" +
            "- For general questions, image descriptions, or conversation, just reply normally. Do NOT call tools.\n" +
            "- When the user asks you to create a project, implement code, or generate files, " +
            "CALL the writeFile tool once per file. Do NOT print the file contents as markdown.\n" +
            "- Use relative paths with forward slashes (e.g. 'src/Main.java', 'pom.xml').\n" +
            "- Write COMPLETE file content — no placeholders, no '...', no truncation.\n" +
            "- After file tool calls finish, give a short one-line summary of what you created.\n" +
            "- Do NOT repeat file contents in your response after calling writeFile.";

    private final OllamaChatModel ollamaChatModel;
    private final OpenAiChatModel openAiChatModel;
    private final OpenAiChatModel unslothChatModel;
    private final ConversationRepository conversationRepository;
    private final FileTools fileTools;
    private final OllamaService ollamaService;
    private final UnslothService unslothService;
    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbacks;

    public ChatService(OllamaChatModel ollamaChatModel,
                       @Qualifier("openAiChatModel") OpenAiChatModel openAiChatModel,
                       @Qualifier("unslothChatModel") OpenAiChatModel unslothChatModel,
                       ConversationRepository conversationRepository, FileTools fileTools,
                       OllamaService ollamaService, UnslothService unslothService,
                       ObjectProvider<ToolCallbackProvider> mcpToolCallbacks) {
        this.ollamaChatModel = ollamaChatModel;
        this.openAiChatModel = openAiChatModel;
        this.unslothChatModel = unslothChatModel;
        this.conversationRepository = conversationRepository;
        this.fileTools = fileTools;
        this.ollamaService = ollamaService;
        this.unslothService = unslothService;
        this.mcpToolCallbacks = mcpToolCallbacks;
    }

    @Transactional(readOnly = true)
    public List<Conversation> getAllConversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public Conversation createConversation(String model, String provider) {
        Conversation conversation = new Conversation("New Chat", model, provider);
        return conversationRepository.save(conversation);
    }

    @Transactional(readOnly = true)
    public Conversation getConversation(Long id) {
        Conversation conversation = conversationRepository.findById(id).orElse(null);
        if (conversation != null) {
            conversation.getMessages().size(); // force lazy load within transaction
        }
        return conversation;
    }

    @Transactional
    public void deleteConversation(Long id) {
        conversationRepository.deleteById(id);
    }

    @Transactional
    public void saveUserMessage(Long conversationId, String content, String imageData, String imageMimeType) {
        Conversation conversation = conversationRepository.findById(conversationId).orElseThrow();
        ChatMessage msg = new ChatMessage(ChatMessage.Role.USER, content);
        if (imageData != null && !imageData.isEmpty()) {
            msg.setImageData(imageData);
            msg.setImageMimeType(imageMimeType);
        }
        conversation.addMessage(msg);

        // Auto-title from first user message
        if (conversation.getMessages().stream().filter(m -> m.getRole() == ChatMessage.Role.USER).count() == 1) {
            String title = content.length() > 50 ? content.substring(0, 50) + "..." : content;
            conversation.setTitle(title);
        }

        conversationRepository.save(conversation);
    }

    @Transactional
    public void saveAssistantMessage(Long conversationId, String content) {
        saveAssistantMessage(conversationId, content, null);
    }

    @Transactional
    public void saveAssistantMessage(Long conversationId, String content, StreamChunk.Usage usage) {
        Conversation conversation = conversationRepository.findById(conversationId).orElseThrow();
        ChatMessage msg = new ChatMessage(ChatMessage.Role.ASSISTANT, content);
        if (usage != null) {
            msg.setInputTokens(usage.inputTokens());
            msg.setOutputTokens(usage.outputTokens());
            msg.setDurationMs(usage.durationMs());
        }
        conversation.addMessage(msg);
        conversationRepository.save(conversation);
    }

    @Transactional(readOnly = true)
    public Flux<StreamChunk> streamResponse(Long conversationId, String model, String provider, boolean useTools, int numCtx) {
        Conversation conversation = conversationRepository.findById(conversationId).orElseThrow();
        conversation.getMessages().size(); // force lazy load

        List<Message> messages = buildMessages(conversation, model, provider, useTools);

        String effectiveProvider = (provider != null) ? provider : "ollama";

        boolean thinking = "ollama".equals(effectiveProvider) && supportsThinking(model);
        boolean vision = supportsVision(model, effectiveProvider);
        boolean nativeTools = useTools && supportsNativeTools(model, effectiveProvider);
        // Surfacing the capability flags makes path selection visible in the log.
        log.info("Capabilities for model={} provider={}: thinking={} vision={} nativeTools={} useTools={}",
                model, effectiveProvider, thinking, vision, nativeTools, useTools);

        // All models route through Spring AI's ChatClient so that MCP tool callbacks
        // (registered as defaultToolCallbacks) are available on every request. Tradeoff:
        // Spring AI 1.1.4's streaming map (OllamaChatModel.internalStream) reads
        // chunk.message().content() but never chunk.message().thinking(), so a reasoning
        // model's thinking trace is silently dropped instead of streamed separately.
        // (Previously a thinking-capable Ollama model bypassed ChatClient via OllamaApi
        // to surface thinking; that path is disabled so MCP tools reach those models too.)
        log.info("Routing via Spring AI ChatClient for provider={} model={}", effectiveProvider, model);

        ChatClient chatClient;

        if ("lmstudio".equals(effectiveProvider)) {
            chatClient = withMcpTools(ChatClient.builder(openAiChatModel)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(0.7)
                            .build()))
                    .build();
        } else if ("unsloth".equals(effectiveProvider)) {
            // Unsloth won't lazy-load on the first chat request — ensure the model
            // is the active loaded one before Spring AI sends the stream.
            unslothService.ensureLoaded(model);
            chatClient = withMcpTools(ChatClient.builder(unslothChatModel)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(0.7)
                            .build()))
                    .build();
        } else {
            OllamaChatOptions.Builder ollamaOpts = OllamaChatOptions.builder()
                    .model(model)
                    .temperature(0.7)
                    .frequencyPenalty(0.0)
                    .presencePenalty(0.0)
                    .numCtx(numCtx);
            if (supportsThinking(model)) {
                ollamaOpts.enableThinking();
            }
            chatClient = withMcpTools(ChatClient.builder(ollamaChatModel)
                    .defaultOptions(ollamaOpts.build()))
                    .build();
        }

        Prompt prompt = new Prompt(messages);

        Flux<StreamChunk> raw;
        if (useTools) {
            // Stream with native tools bound. Spring AI handles the tool-call round-trip
            // inside the stream. If the model doesn't use tools, ChatController runs
            // prompt-parsing on the assembled response in doOnComplete.
            // Streaming avoids the non-streaming read-timeout that Ollama maps to "Model unloaded."
            // when a cold model takes >60s to load.
            fileTools.resetToolCallCount();
            raw = chatClient.prompt(prompt)
                    .tools(fileTools)
                    .stream().chatResponse()
                    .flatMapIterable(ChatService::extractChunks);
        } else {
            raw = chatClient.prompt(prompt)
                    .stream().chatResponse()
                    .flatMapIterable(ChatService::extractChunks);
        }
        return instrument(raw, effectiveProvider, model, useTools);
    }

    private Flux<StreamChunk> instrument(Flux<StreamChunk> src, String provider, String model, boolean useTools) {
        long[] start = new long[1];
        boolean[] first = new boolean[1];
        return src
                .doOnSubscribe(s -> {
                    start[0] = System.currentTimeMillis();
                    log.info("Streaming start: provider={} model={} useTools={}", provider, model, useTools);
                })
                .doOnNext(c -> {
                    if (!first[0]) {
                        first[0] = true;
                        log.info("First {} chunk after {}ms (provider={} model={})",
                                c.kind(), System.currentTimeMillis() - start[0], provider, model);
                    }
                })
                .doOnError(err -> log.warn("Streaming error after {}ms (provider={} model={}): {} - {}",
                        System.currentTimeMillis() - start[0], provider, model,
                        err.getClass().getSimpleName(), err.getMessage()))
                .doOnComplete(() -> log.info("Streaming complete in {}ms (provider={} model={})",
                        System.currentTimeMillis() - start[0], provider, model));
    }

    private static List<StreamChunk> extractChunks(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResult() == null) return List.of();
        var gen = response.getResult();
        List<StreamChunk> out = new ArrayList<>(3);
        // Spring AI's Ollama mapping stores the reasoning trace under the metadata key "thinking".
        // Emit it first so the UI shows reasoning before the answer it explains.
        if (gen.getMetadata() != null) {
            Object thinking = gen.getMetadata().get("thinking");
            if (thinking instanceof String s && !s.isEmpty()) {
                out.add(new StreamChunk(StreamChunk.THINKING, s));
            }
        }
        if (gen.getOutput() != null) {
            String text = gen.getOutput().getText();
            if (text != null && !text.isEmpty()) {
                out.add(new StreamChunk(StreamChunk.CONTENT, text));
            }
        }
        // Usage is typically present only on the final response chunk.
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var u = response.getMetadata().getUsage();
            out.add(new StreamChunk(StreamChunk.USAGE, "",
                    new StreamChunk.Usage(u.getPromptTokens(), u.getCompletionTokens(), null)));
        }
        return out;
    }

    private List<Message> buildMessages(Conversation conversation, String model, String provider, boolean useTools) {
        List<Message> messages = new ArrayList<>();
        String systemPrompt;
        if (useTools) {
            // For Ollama models that advertise the "tools" capability, push them toward native
            // tool calls. Everything else (LM Studio + Ollama models without tools) falls back
            // to the markdown-style prompt and prompt-parsing.
            systemPrompt = supportsNativeTools(model, provider) ? NATIVE_TOOL_SYSTEM_PROMPT : TOOL_SYSTEM_PROMPT;
        } else {
            systemPrompt = SYSTEM_PROMPT;
        }
        messages.add(new SystemMessage(systemPrompt));

        List<ChatMessage> dbMessages = conversation.getMessages();
        for (int i = 0; i < dbMessages.size(); i++) {
            ChatMessage msg = dbMessages.get(i);
            boolean isLast = (i == dbMessages.size() - 1);

            if (isLast && msg.getRole() == ChatMessage.Role.USER) {
                String content = msg.getContent();
                if (dbMessages.size() > 1 && content.trim().split("\\s+").length <= 5) {
                    String lower = content.trim().toLowerCase().replaceAll("[!.,?]+$", "");
                    if (lower.matches("(great|thanks|thank you|ok|okay|got it|nice|cool|perfect|good|awesome|wonderful|excellent|sure|yep|yes|no|nope|alright|fine|understood|right)")) {
                        content = "[The user is acknowledging your previous response. Respond briefly, e.g. 'You're welcome!' or 'Glad I could help!'. Do NOT repeat, rephrase, or redo any previous task.]\n" + content;
                    }
                }
                messages.add(buildUserMessage(content, msg, model, provider));
            } else {
                switch (msg.getRole()) {
                    case USER -> messages.add(buildUserMessage(msg.getContent(), msg, model, provider));
                    case ASSISTANT -> messages.add(new AssistantMessage(msg.getContent()));
                    case SYSTEM -> messages.add(new SystemMessage(msg.getContent()));
                }
            }
        }
        return messages;
    }

    private ChatClient.Builder withMcpTools(ChatClient.Builder builder) {
        ToolCallbackProvider provider = mcpToolCallbacks.getIfAvailable();
        if (provider != null) {
            builder.defaultToolCallbacks(provider);
        }
        return builder;
    }

    private boolean supportsThinking(String model) {
        // Only Ollama has a clean capability flag for reasoning models. We mirror the
        // tools/vision pattern: ask /api/show, opt-in only when the model advertises it.
        return ollamaService.getCapabilities(model).contains("thinking");
    }

    private boolean supportsNativeTools(String model, String provider) {
        // LM Studio doesn't expose a capability API — stay with markdown-style prompt.
        if ("lmstudio".equals(provider)) {
            return false;
        }
        // Unsloth reports supports_tools per-model via /v1/status.
        if ("unsloth".equals(provider)) {
            return unslothService.supportsTools(model);
        }
        // Ollama: always use markdown-style prompt parsing. Native tool calling hides
        // the model's output from the user (no visible code generation), and the user
        // can't see what files are being created or debug formatting issues. Markdown
        // parsing lets the user watch the code stream and copy-paste if extraction fails.
        return false;
    }

    private boolean supportsVision(String model, String provider) {
        // LM Studio: always pass images through — the user controls which model is loaded
        if ("lmstudio".equals(provider)) {
            return true;
        }
        // Unsloth reports is_vision per-model via /v1/status.
        if ("unsloth".equals(provider)) {
            return unslothService.supportsVision(model);
        }
        // Ollama: ask the server directly via /api/show for the "vision" capability flag.
        // This is authoritative — matches exactly what Ollama will do with the image — and
        // avoids false positives from mislabeled community uploads (e.g. a text-only GGUF
        // with a VL-sounding name and a system prompt claiming vision).
        return ollamaService.getCapabilities(model).contains("vision");
    }

    private UserMessage buildUserMessage(String text, ChatMessage msg, String model, String provider) {
        if (msg.getImageData() != null && !msg.getImageData().isEmpty()) {
            if (supportsVision(model, provider)) {
                byte[] imageBytes = Base64.getDecoder().decode(msg.getImageData());
                MimeType mimeType = MimeType.valueOf(msg.getImageMimeType());
                Media media = Media.builder()
                        .mimeType(mimeType)
                        .data(imageBytes)
                        .build();
                return UserMessage.builder()
                        .text(text)
                        .media(media)
                        .build();
            }
            // Non-vision model: skip image, inform the model
            return new UserMessage("[Note: The user attached an image, but this model does not support image analysis. Let them know they should use a vision-capable model like Gemma 4.]\n" + text);
        }
        return new UserMessage(text);
    }
}
