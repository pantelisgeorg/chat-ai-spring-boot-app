// Theme management
function getTheme() {
    return localStorage.getItem('chat-theme') || 'light';
}

function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('chat-theme', theme);
    document.querySelectorAll('.theme-icon').forEach(function(icon) {
        icon.textContent = theme === 'dark' ? '\u2600' : '\u263E';
    });
}

function toggleTheme() {
    var current = getTheme();
    applyTheme(current === 'dark' ? 'light' : 'dark');
}

// Apply saved theme and restore Tools toggle on load.
// The toggle was previously DOM-only and reset on every page reload (sidebar
// click, conversation switch, new chat), so users who flipped it once would
// silently lose the state and the next message went out with useTools=false.
document.addEventListener('DOMContentLoaded', function() {
    applyTheme(getTheme());

    var toggle = document.getElementById('useToolsToggle');
    if (toggle) {
        toggle.checked = localStorage.getItem('useTools') === 'true';
        toggle.addEventListener('change', function() {
            localStorage.setItem('useTools', toggle.checked ? 'true' : 'false');
        });
        if (toggle.checked && typeof toggleWorkspacePanel === 'function') {
            toggleWorkspacePanel();
        }
    }

    // Restore the per-tab num_ctx override, falling back to the server-side default
    // (rendered into the input as data-default). Persist on every change so different
    // tabs / projects can keep different context budgets.
    var ctxInput = document.getElementById('numCtxInput');
    if (ctxInput) {
        var saved = localStorage.getItem('numCtx');
        var fallback = ctxInput.getAttribute('data-default') || '8000';
        ctxInput.value = saved && /^\d+$/.test(saved) ? saved : fallback;
        ctxInput.addEventListener('change', function() {
            if (/^\d+$/.test(ctxInput.value) && parseInt(ctxInput.value, 10) > 0) {
                localStorage.setItem('numCtx', ctxInput.value);
            }
        });
    }

    // Load token usage for current conversation
    var convId = document.getElementById('conversationId');
    if (convId && convId.value) {
        fetchTokenUsage(convId.value);
    }
});

function getNumCtx() {
    var ctxInput = document.getElementById('numCtxInput');
    if (ctxInput && /^\d+$/.test(ctxInput.value) && parseInt(ctxInput.value, 10) > 0) {
        return ctxInput.value;
    }
    return '';
}

// Switch provider and reload model list
function switchProvider(provider) {
    document.getElementById('selectedProvider').value = provider;
    var modelSelect = document.getElementById('modelSelect');
    modelSelect.innerHTML = '<option>Loading...</option>';

    // When switching providers, unload every other provider's models to free memory.
    var providers = ['ollama', 'lmstudio', 'unsloth'];
    providers.forEach(function(p) {
        if (p === provider) return;
        fetch('/config/' + p + '/unload', { method: 'POST' })
            .then(function(r) { return r.json(); })
            .then(function(result) {
                if (result && result.unloaded > 0) {
                    console.log('Unloaded ' + result.unloaded + ' ' + p + ' model(s) to free memory');
                }
            })
            .catch(function() { /* provider may not be running */ });
    });

    fetch('/api/models?provider=' + encodeURIComponent(provider))
        .then(function(r) { return r.json(); })
        .then(function(models) {
            modelSelect.innerHTML = '';
            if (models.length === 0) {
                var opt = document.createElement('option');
                opt.value = '';
                opt.textContent = 'No models available';
                modelSelect.appendChild(opt);
            } else {
                models.forEach(function(m) {
                    var opt = document.createElement('option');
                    opt.value = m;
                    opt.textContent = m;
                    modelSelect.appendChild(opt);
                });
            }
            document.getElementById('selectedModel').value = modelSelect.value;
        })
        .catch(function() {
            modelSelect.innerHTML = '<option value="">Error loading models</option>';
        });
}

// Sidebar toggle (mobile)
function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('collapsed');
}

// Scroll to bottom of messages
function scrollToBottom() {
    var container = document.getElementById('messagesContainer');
    if (container) {
        container.scrollTop = container.scrollHeight;
    }
}

// Auto-resize textarea
var messageInput = document.getElementById('messageInput');
if (messageInput) {
    messageInput.addEventListener('input', function() {
        this.style.height = 'auto';
        this.style.height = Math.min(this.scrollHeight, 200) + 'px';
    });
}

// Handle Enter key (send), Shift+Enter (newline)
function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        document.getElementById('chatForm').dispatchEvent(new Event('submit'));
    }
}

// Send message
function sendMessage(event) {
    event.preventDefault();

    var input = document.getElementById('messageInput');
    var message = input.value.trim();
    if (!message) return;

    var conversationId = document.getElementById('conversationId').value;
    var model = document.getElementById('modelSelect').value;
    var provider = document.getElementById('conversationProvider').value;

    // Build full message with attachments
    var attachmentText = getAttachmentText();
    var fullMessage = attachmentText + message;
    var attachmentNames = pendingFiles.map(function(f) { return f.name; });
    var imageToSend = pendingImage;
    if (imageToSend) {
        attachmentNames.unshift(imageToSend.name);
    }
    clearAttachments();

    // Disable input while processing
    input.disabled = true;
    document.getElementById('sendBtn').disabled = true;

    // Add user message to UI
    var container = document.getElementById('messagesContainer');

    var userDiv = document.createElement('div');
    userDiv.className = 'message user-message';
    userDiv.innerHTML = '<div class="message-role">You</div><div class="message-content"></div>';
    var contentEl = userDiv.querySelector('.message-content');
    if (imageToSend) {
        var img = document.createElement('img');
        img.src = 'data:' + imageToSend.mimeType + ';base64,' + imageToSend.base64;
        img.className = 'chat-image';
        contentEl.appendChild(img);
    }
    var displayText = message;
    if (attachmentNames.length > 0) {
        displayText = '[Attached: ' + attachmentNames.join(', ') + ']\n' + message;
    }
    contentEl.appendChild(document.createTextNode(displayText));
    container.appendChild(userDiv);

    // Add assistant placeholder
    var assistantDiv = document.createElement('div');
    assistantDiv.className = 'message assistant-message';
    assistantDiv.innerHTML = '<div class="message-role">Assistant</div><div class="message-content markdown-body" id="streamTarget"><div class="typing-indicator"><span></span><span></span><span></span></div></div>';
    container.appendChild(assistantDiv);

    scrollToBottom();

    // Save user message on server
    var sendBody = 'message=' + encodeURIComponent(fullMessage);
    if (imageToSend) {
        sendBody += '&imageData=' + encodeURIComponent(imageToSend.base64);
        sendBody += '&imageMimeType=' + encodeURIComponent(imageToSend.mimeType);
    }
    fetch('/chat/' + conversationId + '/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: sendBody
    }).then(function() {
        // Start streaming
        startStreaming(conversationId, model, provider);
    });

    input.value = '';
    input.style.height = 'auto';
}

// Stream AI response via SSE
var activeEventSource = null;
var activeFullText = '';
var activeConversationId = null;

function startStreaming(conversationId, model, provider) {
    var target = document.getElementById('streamTarget');
    if (!target) return;

    activeFullText = '';
    activeConversationId = conversationId;
    var url = '/chat/' + conversationId + '/stream?model=' + encodeURIComponent(model) +
              '&provider=' + encodeURIComponent(provider || 'ollama') +
              '&useTools=' + isToolsEnabled();
    var numCtx = getNumCtx();
    if (numCtx) url += '&numCtx=' + encodeURIComponent(numCtx);

    activeEventSource = new EventSource(url);

    // Show stop button, hide send button
    document.getElementById('sendBtn').style.display = 'none';
    document.getElementById('stopBtn').style.display = 'flex';

    var activeThinkingText = '';
    var thinkingDetails = null;
    var thinkingBody = null;
    var contentHolder = null;

    function ensureContentHolder() {
        if (contentHolder) return contentHolder;
        contentHolder = document.createElement('div');
        contentHolder.className = 'assistant-content';
        target.appendChild(contentHolder);
        return contentHolder;
    }

    function ensureThinkingBlock() {
        if (thinkingDetails) return;
        // Clear the typing-indicator on first arrival of any chunk.
        target.querySelectorAll('.typing-indicator').forEach(function(el) { el.remove(); });
        thinkingDetails = document.createElement('details');
        thinkingDetails.className = 'thinking-block';
        thinkingDetails.open = true;
        var summary = document.createElement('summary');
        summary.textContent = 'Reasoning';
        thinkingBody = document.createElement('div');
        thinkingBody.className = 'thinking-body';
        thinkingDetails.appendChild(summary);
        thinkingDetails.appendChild(thinkingBody);
        target.insertBefore(thinkingDetails, target.firstChild);
    }

    // Throttled render timer (limits expensive markdown re-parsing to ~5/sec max)
    var renderTimer = null;
    var THROTTLE_MS = 200;
    function scheduleRender() {
        if (renderTimer) return;
        renderTimer = setTimeout(function() {
            renderTimer = null;
            renderContent();
        }, THROTTLE_MS);
    }

    activeEventSource.addEventListener('thinking', function(event) {
        var piece;
        try { piece = JSON.parse(event.data).t; } catch (e) { piece = event.data; }
        activeThinkingText += piece;
        ensureThinkingBlock();
        thinkingBody.textContent = activeThinkingText;
        // Scroll only if user is already near bottom (avoid forcing layout every token)
        var container = document.getElementById('messagesContainer');
        if (container && container.scrollTop + container.clientHeight >= container.scrollHeight - 100) {
            scrollToBottom();
        }
    });

    function renderContent() {
        var holder = ensureContentHolder();
        holder.innerHTML = DOMPurify.sanitize(marked.parse(activeFullText), { ADD_ATTR: ['class'] });
        holder.querySelectorAll('pre code').forEach(function(block) {
            if (!block.dataset.highlighted) {
                hljs.highlightElement(block);
                block.dataset.highlighted = 'true';
            }
        });
        scrollToBottom();
    }

    activeEventSource.addEventListener('chunk', function(event) {
        try {
            var parsed = JSON.parse(event.data);
            activeFullText += parsed.t;
        } catch (e) {
            activeFullText += event.data;
        }
        // Collapse the reasoning block once the real answer starts.
        if (thinkingDetails && thinkingDetails.open) {
            thinkingDetails.open = false;
        }
        // Strip the typing-indicator if we never received a thinking chunk.
        target.querySelectorAll('.typing-indicator').forEach(function(el) { el.remove(); });
        scheduleRender();
    });

    activeEventSource.addEventListener('usage', function(event) {
        try {
            var usage = JSON.parse(event.data);
            updateTokenUsage(usage.inputTokens, usage.outputTokens);
        } catch (e) { /* ignore malformed usage */ }
    });

    activeEventSource.addEventListener('done', function() {
        activeEventSource.close();
        activeEventSource = null;
        if (renderTimer) {
            clearTimeout(renderTimer);
            renderTimer = null;
        }
        renderContent(); // force final markdown render
        finishStreaming(target);
        if (isToolsEnabled()) {
            refreshWorkspace();
        }
    });

    activeEventSource.addEventListener('error', function(event) {
        if (event.data) {
            var label = event.data;
            try {
                var parsed = JSON.parse(event.data);
                if (parsed && parsed.type) {
                    label = parsed.type + ': ' + (parsed.message || '');
                }
            } catch (_) { /* plain-text error from older path */ }
            target.innerHTML = '<strong style="color: var(--danger-color);">Error: ' + DOMPurify.sanitize(label) + '</strong>';
        }
        activeEventSource.close();
        activeEventSource = null;
        // Save any partial response that was received before the error
        savePartialResponse();
        finishStreaming(target);
    });

    activeEventSource.onerror = function() {
        if (activeFullText === '') {
            target.innerHTML = '<strong style="color: var(--danger-color);">Connection error. Is the AI provider running?</strong>';
        } else {
            // Had partial content — append a notice so the user knows it was cut off
            var notice = '\n\n---\n*[Response interrupted — partial output saved]*';
            activeFullText += notice;
            renderContent();
        }
        activeEventSource.close();
        activeEventSource = null;
        // Save whatever we received
        savePartialResponse();
        finishStreaming(target);
    };
}

// Save partial response on connection drop or error (prevents lost output)
function savePartialResponse() {
    if (activeFullText && activeConversationId) {
        fetch('/chat/' + activeConversationId + '/save-assistant', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'message=' + encodeURIComponent(activeFullText)
        });
    }
}

function stopStreaming() {
    if (activeEventSource) {
        activeEventSource.close();
        activeEventSource = null;
    }
    if (renderTimer) {
        clearTimeout(renderTimer);
        renderTimer = null;
    }
    var target = document.getElementById('streamTarget');
    if (target && activeFullText) {
        // Ensure final markdown render before saving
        var holder = target.querySelector('.assistant-content') || target;
        holder.innerHTML = DOMPurify.sanitize(marked.parse(activeFullText), { ADD_ATTR: ['class'] });
        holder.querySelectorAll('pre code').forEach(function(block) {
            if (!block.dataset.highlighted) {
                hljs.highlightElement(block);
                block.dataset.highlighted = 'true';
            }
        });
        // Save whatever was generated so far
        fetch('/chat/' + activeConversationId + '/save-assistant', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'message=' + encodeURIComponent(activeFullText)
        });
        addCopyButtons(target);
        target.removeAttribute('id');
    }
    finishStreaming(target);
}

function finishStreaming(target) {
    if (target) {
        addCopyButtons(target);
        target.removeAttribute('id');
    }
    // Show send button, hide stop button
    document.getElementById('sendBtn').style.display = 'flex';
    document.getElementById('stopBtn').style.display = 'none';
    enableInput();
    scrollToBottom();
}

function enableInput() {
    var input = document.getElementById('messageInput');
    var btn = document.getElementById('sendBtn');
    if (input) {
        input.disabled = false;
        input.focus();
    }
    if (btn) {
        btn.disabled = false;
    }
}

// --- Token Usage ---
function updateTokenUsage(inputTokens, outputTokens) {
    var el = document.getElementById('tokenUsage');
    var val = document.getElementById('tokenUsageValue');
    if (!el || !val) return;
    var inTok = inputTokens || 0;
    var outTok = outputTokens || 0;
    var total = inTok + outTok;
    val.textContent = total.toLocaleString() + ' (' + inTok + '↓ ' + outTok + '↑)';
    el.style.display = 'flex';
}

function fetchTokenUsage(conversationId) {
    if (!conversationId) return;
    fetch('/chat/' + conversationId + '/usage')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data && data.total > 0) {
                updateTokenUsage(data.totalInput, data.totalOutput);
            }
        })
        .catch(function() { /* ignore */ });
}

// --- File Attachment ---
var pendingFiles = [];
var pendingImage = null;

var IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp'];

function isImageFile(filename) {
    var ext = filename.split('.').pop().toLowerCase();
    return IMAGE_EXTENSIONS.indexOf(ext) !== -1;
}

function handleFileSelect(input) {
    var files = Array.from(input.files);
    files.forEach(function(file) {
        if (isImageFile(file.name)) {
            if (file.size > 5 * 1024 * 1024) {
                alert('Image "' + file.name + '" is too large (max 5MB).');
                return;
            }
            var reader = new FileReader();
            reader.onload = function(e) {
                // e.target.result is "data:<mime>;base64,<data>"
                var dataUrl = e.target.result;
                var mimeType = dataUrl.substring(dataUrl.indexOf(':') + 1, dataUrl.indexOf(';'));
                var base64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                pendingImage = { name: file.name, base64: base64, mimeType: mimeType };
                renderAttachmentPreview();
            };
            reader.readAsDataURL(file);
        } else {
            if (file.size > 512 * 1024) {
                alert('File "' + file.name + '" is too large (max 512KB).');
                return;
            }
            var reader = new FileReader();
            reader.onload = function(e) {
                pendingFiles.push({ name: file.name, content: e.target.result });
                renderAttachmentPreview();
            };
            reader.readAsText(file);
        }
    });
    input.value = '';
}

function renderAttachmentPreview() {
    var preview = document.getElementById('attachmentPreview');
    var chips = preview.querySelector('.attachment-chips');
    if (pendingFiles.length === 0 && !pendingImage) {
        preview.style.display = 'none';
        return;
    }
    preview.style.display = 'block';
    chips.innerHTML = '';
    if (pendingImage) {
        var chip = document.createElement('span');
        chip.className = 'attachment-chip';
        chip.innerHTML = '<img src="data:' + pendingImage.mimeType + ';base64,' + pendingImage.base64 + '" class="attachment-thumb"> ' + pendingImage.name + ' <button onclick="removeImage()">&times;</button>';
        chips.appendChild(chip);
    }
    pendingFiles.forEach(function(f, i) {
        var chip = document.createElement('span');
        chip.className = 'attachment-chip';
        chip.innerHTML = '&#128196; ' + f.name + ' <button onclick="removeAttachment(' + i + ')">&times;</button>';
        chips.appendChild(chip);
    });
}

function removeAttachment(index) {
    pendingFiles.splice(index, 1);
    renderAttachmentPreview();
}

function removeImage() {
    pendingImage = null;
    renderAttachmentPreview();
}

function getAttachmentText() {
    if (pendingFiles.length === 0) return '';
    var text = '';
    pendingFiles.forEach(function(f) {
        text += '[Attached file: ' + f.name + ']\n' + f.content + '\n\n';
    });
    return text;
}

function clearAttachments() {
    pendingFiles = [];
    pendingImage = null;
    renderAttachmentPreview();
}

// --- Workspace / Tools ---
var currentWorkspacePath = '';
var currentPreviewPath = '';

function isToolsEnabled() {
    var toggle = document.getElementById('useToolsToggle');
    return toggle && toggle.checked;
}

function toggleWorkspacePanel() {
    var panel = document.getElementById('workspacePanel');
    if (!panel) return;
    var enabled = isToolsEnabled();
    panel.style.display = enabled ? 'flex' : 'none';
    if (enabled) {
        refreshWorkspace();
    }
}

function closeWorkspacePanel() {
    var panel = document.getElementById('workspacePanel');
    if (panel) panel.style.display = 'none';
    var toggle = document.getElementById('useToolsToggle');
    if (toggle) {
        toggle.checked = false;
        localStorage.setItem('useTools', 'false');
    }
}

function refreshWorkspace() {
    browseWorkspace(currentWorkspacePath);
}

function browseWorkspace(path) {
    currentWorkspacePath = path || '';
    fetch('/workspace/files?path=' + encodeURIComponent(currentWorkspacePath))
        .then(function(r) { return r.json(); })
        .then(function(files) {
            renderWorkspaceFiles(files);
            renderBreadcrumb(currentWorkspacePath);
        })
        .catch(function() {
            document.getElementById('workspaceFiles').innerHTML =
                '<div class="workspace-empty">Could not load workspace files</div>';
        });
}

function renderBreadcrumb(path) {
    var container = document.getElementById('workspaceBreadcrumb');
    container.innerHTML = '';
    var root = document.createElement('span');
    root.className = 'breadcrumb-item';
    root.textContent = 'workspace';
    root.onclick = function() { browseWorkspace(''); };
    container.appendChild(root);

    if (path) {
        var parts = path.split('/');
        var cumulative = '';
        parts.forEach(function(part) {
            cumulative += (cumulative ? '/' : '') + part;
            var sep = document.createElement('span');
            sep.className = 'breadcrumb-sep';
            sep.textContent = ' / ';
            container.appendChild(sep);

            var crumb = document.createElement('span');
            crumb.className = 'breadcrumb-item';
            crumb.textContent = part;
            var p = cumulative;
            crumb.onclick = function() { browseWorkspace(p); };
            container.appendChild(crumb);
        });
    }
}

function renderWorkspaceFiles(files) {
    var container = document.getElementById('workspaceFiles');
    if (!files || files.length === 0) {
        container.innerHTML = '<div class="workspace-empty">Empty — ask the model to create a project</div>';
        return;
    }
    container.innerHTML = '';

    // Add parent directory link if not at root
    if (currentWorkspacePath) {
        var parentItem = document.createElement('div');
        parentItem.className = 'ws-file-item';
        parentItem.innerHTML = '<span class="ws-file-icon">&#128281;</span><span class="ws-file-name">..</span>';
        parentItem.onclick = function() {
            var parts = currentWorkspacePath.split('/');
            parts.pop();
            browseWorkspace(parts.join('/'));
        };
        container.appendChild(parentItem);
    }

    files.forEach(function(f) {
        var item = document.createElement('div');
        item.className = 'ws-file-item';

        var icon = f.directory ? '&#128193;' : getFileIcon(f.name);
        var size = f.size != null ? formatSize(f.size) : '';

        item.innerHTML =
            '<span class="ws-file-icon">' + icon + '</span>' +
            '<span class="ws-file-name">' + escapeHtml(f.name) + '</span>' +
            (size ? '<span class="ws-file-size">' + size + '</span>' : '') +
            '<span class="ws-file-actions">' +
            (f.directory ? '' : '<button title="Download" onclick="event.stopPropagation();downloadFile(\'' + escapeAttr(f.path) + '\')">&#11015;</button>') +
            '<button class="btn-ws-delete" title="Delete" onclick="event.stopPropagation();deleteWorkspaceFile(\'' + escapeAttr(f.path) + '\')">&#128465;</button>' +
            '</span>';

        item.onclick = function() {
            if (f.directory) {
                browseWorkspace(f.path);
            } else {
                previewFile(f.path, f.name);
            }
        };
        container.appendChild(item);
    });
}

function previewFile(path, name) {
    currentPreviewPath = path;
    fetch('/workspace/read?path=' + encodeURIComponent(path))
        .then(function(r) { return r.text(); })
        .then(function(content) {
            document.getElementById('previewFileName').textContent = name;
            var codeEl = document.getElementById('previewContent');
            codeEl.textContent = content;
            // Try to highlight
            if (typeof hljs !== 'undefined') {
                hljs.highlightElement(codeEl);
            }
            document.getElementById('workspacePreview').style.display = 'flex';
        });
}

function closePreview() {
    document.getElementById('workspacePreview').style.display = 'none';
    currentPreviewPath = '';
}

function downloadFile(path) {
    window.open('/workspace/download?path=' + encodeURIComponent(path), '_blank');
}

function downloadWorkspaceFile() {
    if (currentPreviewPath) {
        downloadFile(currentPreviewPath);
    }
}

function deleteWorkspaceFile(path) {
    if (!confirm('Delete "' + path + '"?')) return;
    fetch('/workspace/files?path=' + encodeURIComponent(path), { method: 'DELETE' })
        .then(function() { refreshWorkspace(); });
}

// --- Extract Files (manual fallback) ---
function extractFiles() {
    var conversationId = document.getElementById('conversationId');
    if (!conversationId) return;

    var btn = document.getElementById('extractFilesBtn');
    if (btn) {
        btn.disabled = true;
        btn.textContent = 'Extracting...';
    }

    fetch('/chat/' + conversationId.value + '/extract-files', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(result) {
            if (result.count > 0) {
                refreshWorkspace();
                alert('Extracted ' + result.count + ' file(s):\n' + result.files.join('\n'));
            } else {
                alert('No extractable file blocks found in this conversation.\nResponses are still saved in workspace/.archive/');
            }
        })
        .catch(function() {
            alert('Failed to extract files. Check the server logs.');
        })
        .finally(function() {
            if (btn) {
                btn.disabled = false;
                btn.textContent = 'Extract Files';
            }
        });
}

function getFileIcon(name) {
    var ext = name.split('.').pop().toLowerCase();
    var icons = {
        java: '&#9749;', py: '&#128013;', js: '&#127312;', ts: '&#127319;',
        html: '&#127760;', css: '&#127912;', json: '&#128196;', xml: '&#128196;',
        md: '&#128221;', txt: '&#128196;', yaml: '&#128196;', yml: '&#128196;',
        sh: '&#128187;', sql: '&#128451;', gradle: '&#128230;', properties: '&#9881;',
        toml: '&#9881;', cfg: '&#9881;', ini: '&#9881;'
    };
    return icons[ext] || '&#128196;';
}

function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function escapeHtml(text) {
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function escapeAttr(text) {
    return text.replace(/'/g, "\\'").replace(/"/g, '&quot;');
}

// --- Code Block Header with Language Label & Copy Button ---
function addCopyButtons(container) {
    container.querySelectorAll('pre').forEach(function(pre) {
        if (pre.querySelector('.code-header')) return;
        var code = pre.querySelector('code') || pre;

        // Detect language from class
        var lang = '';
        if (code.className) {
            var match = code.className.match(/language-(\w+)/);
            if (match) lang = match[1];
        }

        // Apply highlight.js
        if (typeof hljs !== 'undefined' && code.tagName === 'CODE') {
            hljs.highlightElement(code);
        }

        // Create header bar
        var header = document.createElement('div');
        header.className = 'code-header';
        header.innerHTML = '<span class="code-lang">' + (lang || 'code') + '</span>';

        var btn = document.createElement('button');
        btn.className = 'btn-copy';
        btn.textContent = 'Copy';
        btn.title = 'Copy code';
        btn.addEventListener('click', function() {
            navigator.clipboard.writeText(code.textContent).then(function() {
                btn.textContent = 'Copied!';
                setTimeout(function() { btn.textContent = 'Copy'; }, 2000);
            });
        });
        header.appendChild(btn);

        pre.style.position = 'relative';
        pre.insertBefore(header, pre.firstChild);
    });
}
