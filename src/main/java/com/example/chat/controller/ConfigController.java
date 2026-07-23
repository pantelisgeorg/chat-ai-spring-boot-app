package com.example.chat.controller;

import com.example.chat.service.LmStudioService;
import com.example.chat.service.OllamaService;
import com.example.chat.service.UnslothService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/config")
public class ConfigController {

    private final OllamaService ollamaService;
    private final LmStudioService lmStudioService;
    private final UnslothService unslothService;

    public ConfigController(OllamaService ollamaService, LmStudioService lmStudioService,
                            UnslothService unslothService) {
        this.ollamaService = ollamaService;
        this.lmStudioService = lmStudioService;
        this.unslothService = unslothService;
    }

    @GetMapping
    public String config(Model model) {
        List<String> models = ollamaService.listModels();
        model.addAttribute("models", models);
        model.addAttribute("ollamaAvailable", ollamaService.isAvailable());
        model.addAttribute("lmstudioModels", lmStudioService.listModels());
        model.addAttribute("lmstudioAvailable", lmStudioService.isAvailable());
        return "config";
    }

    @GetMapping("/models")
    public String modelList(Model model) {
        model.addAttribute("models", ollamaService.listModels());
        return "config :: modelList";
    }

    @PostMapping("/pull")
    @ResponseBody
    public String pullModel(@RequestParam String modelName) {
        return ollamaService.pullModel(modelName);
    }

    /**
     * Unload all Ollama models from memory. Called when switching to LM Studio
     * to free up RAM/VRAM on memory-constrained systems.
     */
    @PostMapping("/ollama/unload")
    @ResponseBody
    public Map<String, Object> unloadOllamaModels() {
        List<String> running = ollamaService.getRunningModels();
        int unloaded = ollamaService.unloadAllModels();
        return Map.of(
                "unloaded", unloaded,
                "models", running
        );
    }

    /**
     * Unload all LM Studio models from memory. Called when switching to Ollama
     * to free up RAM/VRAM on memory-constrained systems.
     */
    @PostMapping("/lmstudio/unload")
    @ResponseBody
    public Map<String, Object> unloadLmStudioModels() {
        List<String> loaded = lmStudioService.listModels();
        int unloaded = lmStudioService.unloadAllModels();
        return Map.of(
                "unloaded", unloaded,
                "models", loaded
        );
    }

    @PostMapping("/unsloth/unload")
    @ResponseBody
    public Map<String, Object> unloadUnslothModels() {
        List<String> loaded = unslothService.listModels();
        int unloaded = unslothService.unloadAllModels();
        return Map.of(
                "unloaded", unloaded,
                "models", loaded
        );
    }
}
