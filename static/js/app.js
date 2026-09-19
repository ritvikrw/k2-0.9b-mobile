let currentChatId = null;
let isGenerating = false;
let chats = [];
let activeFilter = "all";
let activeSearchQuery = "";
let currentAttachments = [];
let currentEventSource = null;
let speechRecognition = null;
let isListening = false;
let currentUtterance = null;
let currentlySpeakingBtn = null;

// DOM Elements
const chatListEl = document.getElementById("chat-list");
const landingViewEl = document.getElementById("landing-view");
const messagesListEl = document.getElementById("messages-list");
const chatContainerEl = document.getElementById("chat-container");
const chatTitleEl = document.getElementById("chat-title");
const lockedModelBadgeEl = document.getElementById("locked-model-badge");
const badgeTextEl = document.getElementById("badge-text");
const chatFormEl = document.getElementById("chat-form");
const userInputEl = document.getElementById("user-input");
const sendBtnEl = document.getElementById("send-btn");
const stopGenBtn = document.getElementById("stop-gen-btn");
const newChatBtn = document.getElementById("new-chat-btn");
const liveSpeedometerEl = document.getElementById("live-speedometer");
const liveSpeedValEl = document.getElementById("live-speed-val");
const liveTimeValEl = document.getElementById("live-time-val");
const footerStatusEl = document.getElementById("footer-status");
const chatSearchEl = document.getElementById("chat-search");
const personaSelectEl = document.getElementById("persona-select");
const exportChatBtn = document.getElementById("export-chat-btn");

// Mobile Drawer Elements
const sidebarEl = document.getElementById("sidebar");
const toggleSidebarBtn = document.getElementById("toggle-sidebar-btn");
const closeSidebarBtn = document.getElementById("close-sidebar-btn");
const sidebarBackdropEl = document.getElementById("sidebar-backdrop");

// Voice & Attachment Elements
const micBtn = document.getElementById("mic-btn");
const voiceRecordingIndicator = document.getElementById("voice-recording-indicator");
const stopMicBtn = document.getElementById("stop-mic-btn");
const attachMenuBtn = document.getElementById("attach-menu-btn");
const attachDropdown = document.getElementById("attach-dropdown");
const uploadDocBtn = document.getElementById("upload-doc-btn");
const uploadCameraBtn = document.getElementById("upload-camera-btn");
const uploadImageBtn = document.getElementById("upload-image-btn");
const docFileInput = document.getElementById("doc-file-input");
const cameraFileInput = document.getElementById("camera-file-input");
const galleryFileInput = document.getElementById("gallery-file-input");
const attachmentsTray = document.getElementById("attachments-tray");
const attachmentsList = document.getElementById("attachments-list");

// Auto-grow textarea
userInputEl.addEventListener("input", function() {
  this.style.height = "auto";
  this.style.height = Math.min(this.scrollHeight, 180) + "px";
});

// Submit on Enter (without Shift)
userInputEl.addEventListener("keydown", function(e) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    if (!isGenerating && (userInputEl.value.trim() || currentAttachments.length > 0)) {
      chatFormEl.dispatchEvent(new Event("submit"));
    }
  }
});

function refreshIcons() {
  if (window.lucide) lucide.createIcons();
}

// ---------------- Mobile Drawer Management ----------------
function openSidebar() {
  sidebarEl.classList.remove("-translate-x-full");
  sidebarBackdropEl.classList.remove("hidden");
}

function closeSidebar() {
  sidebarEl.classList.add("-translate-x-full");
  sidebarBackdropEl.classList.add("hidden");
}

if (toggleSidebarBtn) toggleSidebarBtn.addEventListener("click", openSidebar);
if (closeSidebarBtn) closeSidebarBtn.addEventListener("click", closeSidebar);
if (sidebarBackdropEl) sidebarBackdropEl.addEventListener("click", closeSidebar);

// ---------------- Speech-to-Text (Microphone) ----------------
const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
if (SpeechRecognition) {
  speechRecognition = new SpeechRecognition();
  speechRecognition.continuous = true;
  speechRecognition.interimResults = true;
  speechRecognition.lang = "en-US";

  speechRecognition.onresult = (event) => {
    let transcript = "";
    for (let i = event.resultIndex; i < event.results.length; i++) {
      transcript += event.results[i][0].transcript;
    }
    userInputEl.value = (userInputEl.value ? userInputEl.value + " " : "") + transcript;
    userInputEl.style.height = "auto";
    userInputEl.style.height = Math.min(userInputEl.scrollHeight, 180) + "px";
  };

  speechRecognition.onerror = (event) => {
    console.warn("Speech recognition error:", event.error);
    stopListening();
  };

  speechRecognition.onend = () => {
    if (isListening) {
      stopListening();
    }
  };
}

function startListening() {
  if (!speechRecognition) {
    alert("Speech recognition is not supported in this browser. Please use Chrome/Safari.");
    return;
  }
  try {
    speechRecognition.start();
    isListening = true;
    micBtn.classList.add("mic-active");
    voiceRecordingIndicator.classList.remove("hidden");
  } catch (err) {
    console.error("Mic start error:", err);
  }
}

function stopListening() {
  if (speechRecognition && isListening) {
    speechRecognition.stop();
  }
  isListening = false;
  micBtn.classList.remove("mic-active");
  voiceRecordingIndicator.classList.add("hidden");
}

micBtn.addEventListener("click", () => {
  if (isListening) stopListening();
  else startListening();
});

stopMicBtn.addEventListener("click", stopListening);

// ---------------- Text-to-Speech (Audio Readout) ----------------
function toggleTTS(text, btnElement) {
  if (!window.speechSynthesis) {
    alert("Text-to-speech is not supported on this browser.");
    return;
  }

  if (window.speechSynthesis.speaking) {
    window.speechSynthesis.cancel();
    if (currentlySpeakingBtn) {
      currentlySpeakingBtn.classList.remove("speaking");
      currentlySpeakingBtn = null;
    }
    return;
  }

  const cleanText = cleanSpecial(text).replace(/[#*`_~]/g, "");
  currentUtterance = new SpeechSynthesisUtterance(cleanText);
  currentUtterance.rate = 1.0;
  currentUtterance.pitch = 1.0;

  currentUtterance.onstart = () => {
    currentlySpeakingBtn = btnElement;
    btnElement.classList.add("speaking");
  };

  currentUtterance.onend = () => {
    btnElement.classList.remove("speaking");
    currentlySpeakingBtn = null;
  };

  currentUtterance.onerror = () => {
    btnElement.classList.remove("speaking");
    currentlySpeakingBtn = null;
  };

  window.speechSynthesis.speak(currentUtterance);
}

// ---------------- File & Photo Attachments ----------------
attachMenuBtn.addEventListener("click", (e) => {
  e.stopPropagation();
  attachDropdown.classList.toggle("hidden");
});

document.addEventListener("click", () => {
  attachDropdown.classList.add("hidden");
});

uploadDocBtn.addEventListener("click", () => {
  attachDropdown.classList.add("hidden");
  docFileInput.click();
});

uploadCameraBtn.addEventListener("click", () => {
  attachDropdown.classList.add("hidden");
  cameraFileInput.click();
});

uploadImageBtn.addEventListener("click", () => {
  attachDropdown.classList.add("hidden");
  galleryFileInput.click();
});

[docFileInput, cameraFileInput, galleryFileInput].forEach(input => {
  input.addEventListener("change", async (e) => {
    const files = Array.from(e.target.files);
    for (const file of files) {
      await handleFileUpload(file);
    }
    input.value = "";
  });
});

async function handleFileUpload(file) {
  footerStatusEl.textContent = `Uploading ${file.name}...`;
  const formData = new FormData();
  formData.append("file", file);

  try {
    const res = await fetch("/api/upload", {
      method: "POST",
      body: formData
    });
    if (!res.ok) throw new Error("Upload failed");
    const data = await res.json();
    currentAttachments.push(data);
    renderAttachmentsTray();
    footerStatusEl.textContent = "Attachment ready";
  } catch (err) {
    console.error("Upload error:", err);
    alert(`Failed to process ${file.name}: ${err.message}`);
    footerStatusEl.textContent = "Upload failed";
  }
}

function renderAttachmentsTray() {
  if (currentAttachments.length === 0) {
    attachmentsTray.classList.add("hidden");
    attachmentsList.innerHTML = "";
    return;
  }

  attachmentsTray.classList.remove("hidden");
  attachmentsList.innerHTML = "";

  currentAttachments.forEach((att, index) => {
    const pill = document.createElement("div");
    pill.className = "attachment-pill";
    
    let iconName = "file-text";
    if (att.type === "pdf") iconName = "file";
    else if (att.type === "image") iconName = "image";
    else if (att.type === "code") iconName = "code";

    pill.innerHTML = `
      <i data-lucide="${iconName}" class="w-3.5 h-3.5 text-emerald-400"></i>
      <span class="max-w-[150px] truncate font-medium">${escapeHtml(att.name)}</span>
      <span class="text-[10px] text-gray-500">(${att.size_kb} KB)</span>
      <button type="button" onclick="removeAttachment(${index})" class="attachment-pill-remove">
        <i data-lucide="x" class="w-3 h-3"></i>
      </button>
    `;
    attachmentsList.appendChild(pill);
  });
  refreshIcons();
}

window.removeAttachment = function(index) {
  currentAttachments.splice(index, 1);
  renderAttachmentsTray();
};

// ---------------- Chat List & Search ----------------
async function loadChats() {
  try {
    const res = await fetch("/api/chats");
    const data = await res.json();
    chats = data.chats || [];
    renderChatList();
  } catch (err) {
    console.error("Failed to load chats:", err);
  }
}

function renderChatList() {
  chatListEl.innerHTML = "";
  const filtered = chats.filter(c => {
    const matchModel = activeFilter === "all" || c.model === activeFilter;
    const matchSearch = !activeSearchQuery || c.title.toLowerCase().includes(activeSearchQuery.toLowerCase());
    return matchModel && matchSearch;
  });

  if (filtered.length === 0) {
    chatListEl.innerHTML = `<div class="text-xs text-gray-500 text-center py-6">No conversations found</div>`;
    return;
  }

  filtered.forEach(chat => {
    const isActive = chat.id === currentChatId;
    const is09B = chat.model === "0.9b";
    
    const div = document.createElement("div");
    div.className = `group flex items-center justify-between px-3 py-2 rounded-lg cursor-pointer transition text-xs ${
      isActive ? "bg-dark-800 text-white font-medium border border-dark-700" : "text-gray-400 hover:bg-dark-850 hover:text-gray-200"
    }`;

    div.innerHTML = `
      <div class="flex items-center space-x-2.5 truncate flex-1" onclick="selectChat('${chat.id}')">
        <span class="w-2 h-2 rounded-full flex-shrink-0 ${is09B ? 'bg-emerald-400' : 'bg-indigo-400'}"></span>
        <span class="truncate">${escapeHtml(chat.title)}</span>
      </div>
      <div class="flex items-center space-x-1 opacity-0 group-hover:opacity-100 transition flex-shrink-0">
        <button onclick="deleteChat(event, '${chat.id}')" class="p-1 hover:text-red-400 rounded transition" title="Delete">
          <i data-lucide="trash-2" class="w-3.5 h-3.5"></i>
        </button>
      </div>
    `;
    chatListEl.appendChild(div);
  });
  refreshIcons();
}

chatSearchEl.addEventListener("input", (e) => {
  activeSearchQuery = e.target.value.trim();
  renderChatList();
});

// ---------------- Chat Selection & Details ----------------
async function selectChat(chatId) {
  if (isGenerating) return;
  currentChatId = chatId;
  closeSidebar();
  renderChatList();

  try {
    const res = await fetch(`/api/chats/${chatId}`);
    const data = await res.json();
    const chat = data.chat;
    const messages = data.messages || [];

    chatTitleEl.textContent = chat.title;
    lockedModelBadgeEl.classList.remove("hidden");
    lockedModelBadgeEl.classList.add("flex");
    exportChatBtn.classList.remove("hidden");
    
    if (chat.persona && personaSelectEl) {
      personaSelectEl.value = chat.persona;
    }

    if (chat.model === "0.9b") {
      lockedModelBadgeEl.className = "px-2.5 py-0.5 rounded-full text-[11px] font-mono font-medium border flex items-center space-x-1 bg-emerald-500/10 border-emerald-500/30 text-emerald-400";
      badgeTextEl.textContent = "⚡ 0.9B Q4 (4-Bit)";
    } else {
      lockedModelBadgeEl.className = "px-2.5 py-0.5 rounded-full text-[11px] font-mono font-medium border flex items-center space-x-1 bg-indigo-500/10 border-indigo-500/30 text-indigo-400";
      badgeTextEl.textContent = "🧠 7B Reasoner";
    }

    if (messages.length === 0) {
      landingViewEl.classList.remove("hidden");
      messagesListEl.classList.add("hidden");
    } else {
      landingViewEl.classList.add("hidden");
      messagesListEl.classList.remove("hidden");
      renderMessages(messages);
    }
  } catch (err) {
    console.error("Error loading chat details:", err);
  }
}

async function createNewChat(modelKey, initialPersona = "default") {
  if (isGenerating) return;
  try {
    const res = await fetch("/api/chats", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ model: modelKey, title: "New Conversation", persona: initialPersona })
    });
    const chat = await res.json();
    await loadChats();
    selectChat(chat.id);
  } catch (err) {
    console.error("Error creating chat:", err);
  }
}

async function deleteChat(e, chatId) {
  e.stopPropagation();
  if (isGenerating) return;
  if (!confirm("Delete this conversation?")) return;

  try {
    await fetch(`/api/chats/${chatId}`, { method: "DELETE" });
    if (currentChatId === chatId) {
      currentChatId = null;
      showLandingView();
    }
    await loadChats();
  } catch (err) {
    console.error("Error deleting chat:", err);
  }
}

function showLandingView() {
  chatTitleEl.textContent = "K2 Horizon AI";
  lockedModelBadgeEl.classList.add("hidden");
  lockedModelBadgeEl.classList.remove("flex");
  exportChatBtn.classList.add("hidden");
  landingViewEl.classList.remove("hidden");
  messagesListEl.classList.add("hidden");
  messagesListEl.innerHTML = "";
  currentChatId = null;
  currentAttachments = [];
  renderAttachmentsTray();
  renderChatList();
}

// ---------------- Persona Selector & Export ----------------
personaSelectEl.addEventListener("change", async (e) => {
  const newPersona = e.target.value;
  if (currentChatId) {
    await fetch(`/api/chats/${currentChatId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ persona: newPersona })
    });
    footerStatusEl.textContent = `Persona: ${newPersona}`;
  }
});

exportChatBtn.addEventListener("click", () => {
  if (!currentChatId) return;
  window.open(`/api/chats/${currentChatId}/export?format=markdown`, "_blank");
});

// ---------------- Messages Rendering ----------------
function renderMessages(messages) {
  messagesListEl.innerHTML = "";
  messages.forEach(msg => {
    appendMessageToUI(msg.role, msg.content, msg.thinking, msg.metrics, msg.attachments, msg.id);
  });
  scrollToBottom();
}

function appendMessageToUI(role, content, thinking = "", metrics = null, attachments = [], messageId = "") {
  const isUser = role === "user";
  const div = document.createElement("div");
  div.className = `flex ${isUser ? "justify-end" : "justify-start"} w-full message-card`;

  if (isUser) {
    let attachmentsHtml = "";
    if (attachments && attachments.length > 0) {
      attachmentsHtml = `<div class="flex flex-wrap gap-1.5 mb-2">`;
      attachments.forEach(att => {
        if (att.preview) {
          attachmentsHtml += `<img src="${att.preview}" class="msg-image-preview">`;
        } else {
          attachmentsHtml += `
            <div class="px-2 py-1 rounded bg-dark-900/80 border border-dark-700/80 text-[11px] text-emerald-400 flex items-center space-x-1 font-mono">
              <i data-lucide="file-text" class="w-3 h-3"></i>
              <span class="max-w-[120px] truncate">${escapeHtml(att.name)}</span>
            </div>
          `;
        }
      });
      attachmentsHtml += `</div>`;
    }

    div.innerHTML = `
      <div class="max-w-2xl bg-emerald-600/15 border border-emerald-500/30 text-gray-100 rounded-2xl rounded-tr-sm px-3.5 py-2.5 text-xs sm:text-sm shadow group">
        ${attachmentsHtml}
        <div class="whitespace-pre-wrap leading-relaxed">${escapeHtml(content)}</div>
        <div class="msg-actions justify-end">
          <button onclick="editPrompt(this, '${messageId}')" class="action-btn" title="Edit prompt">
            <i data-lucide="pencil" class="w-3.5 h-3.5"></i>
          </button>
          <button onclick="copyText('${escapeForJs(content)}')" class="action-btn" title="Copy text">
            <i data-lucide="copy" class="w-3.5 h-3.5"></i>
          </button>
        </div>
      </div>
    `;
  } else {
    let thinkingHtml = "";
    if (thinking && thinking.trim()) {
      const dur = metrics && metrics.total_time_s ? `(${metrics.total_time_s}s)` : "";
      thinkingHtml = `
        <details class="thinking-box">
          <summary class="thinking-header">
            <span class="flex items-center space-x-1.5">
              <i data-lucide="brain" class="w-3.5 h-3.5 text-indigo-400"></i>
              <span>Thought Process ${dur}</span>
            </span>
            <span class="text-[10px] text-gray-500">Expand</span>
          </summary>
          <div class="thinking-content">${escapeHtml(cleanSpecial(thinking))}</div>
        </details>
      `;
    }

    let metricsHtml = "";
    if (metrics && metrics.ttft_ms !== undefined) {
      const quant = metrics.quantization || "4-Bit Q4";
      metricsHtml = `
        <div class="latency-card">
          <div class="latency-item">
            <span>⏱️ TTFT:</span>
            <strong class="text-emerald-400">${metrics.ttft_ms}ms</strong>
          </div>
          <span class="text-gray-600">&bull;</span>
          <div class="latency-item">
            <span>⚡ Speed:</span>
            <strong class="text-emerald-400">${metrics.tok_per_sec} tok/s</strong>
          </div>
          <span class="text-gray-600">&bull;</span>
          <div class="latency-item">
            <span>🕒 Total:</span>
            <strong>${metrics.total_time_s}s</strong>
          </div>
          <span class="text-gray-600">&bull;</span>
          <div class="latency-item">
            <span class="text-emerald-400/90 font-mono">${quant}</span>
          </div>
        </div>
      `;
    }

    const cleanContent = cleanSpecial(content || "");
    const parsedContent = marked.parse(cleanContent);

    div.innerHTML = `
      <div class="w-full max-w-3xl space-y-1.5">
        <div class="flex items-center space-x-2 text-xs text-gray-400">
          <div class="w-5 h-5 rounded-md bg-gradient-to-tr from-emerald-500 to-indigo-600 flex items-center justify-center text-[10px] font-bold text-white">K2</div>
          <span class="font-medium text-gray-300">K2 Horizon AI</span>
        </div>
        ${thinkingHtml}
        <div class="prose text-gray-100 text-xs sm:text-sm leading-relaxed">${parsedContent}</div>
        ${metricsHtml}
        
        <!-- Assistant Action Bar -->
        <div class="msg-actions">
          <button onclick="copyText('${escapeForJs(cleanContent)}')" class="action-btn" title="Copy response">
            <i data-lucide="copy" class="w-3.5 h-3.5"></i>
          </button>
          <button onclick="toggleTTS('${escapeForJs(cleanContent)}', this)" class="action-btn" title="Read Aloud (Voice)">
            <i data-lucide="volume-2" class="w-3.5 h-3.5"></i>
          </button>
          ${messageId ? `
          <button onclick="regenerateResponse('${messageId}')" class="action-btn" title="Regenerate Response">
            <i data-lucide="rotate-cw" class="w-3.5 h-3.5"></i>
          </button>` : ''}
        </div>
      </div>
    `;
  }

  messagesListEl.appendChild(div);
  refreshIcons();
  attachCopyButtons();
}

// ---------------- Form Submit & Streaming ----------------
chatFormEl.addEventListener("submit", async function(e) {
  e.preventDefault();
  if (isGenerating) return;

  const text = userInputEl.value.trim();
  const attachments = [...currentAttachments];

  if (!text && attachments.length === 0) return;

  if (!currentChatId) {
    const selectedPersona = personaSelectEl ? personaSelectEl.value : "default";
    await createNewChat("0.9b", selectedPersona);
  }

  isGenerating = true;
  userInputEl.value = "";
  userInputEl.style.height = "auto";
  currentAttachments = [];
  renderAttachmentsTray();

  sendBtnEl.classList.add("hidden");
  stopGenBtn.classList.remove("hidden");
  footerStatusEl.textContent = "Processing...";

  landingViewEl.classList.add("hidden");
  messagesListEl.classList.remove("hidden");

  // Append user message
  appendMessageToUI("user", text || "[Sent Attachment]", "", null, attachments);
  scrollToBottom();

  // Send message to backend
  try {
    await fetch(`/api/chats/${currentChatId}/message`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content: text, attachments: attachments })
    });
    await loadChats();
  } catch (err) {
    console.error("Failed to send message:", err);
  }

  // Create Assistant bubble
  const assistantBubble = document.createElement("div");
  assistantBubble.className = "flex justify-start w-full";
  const msgUid = "msg_" + Math.random().toString(36).substring(2, 9);
  
  assistantBubble.innerHTML = `
    <div class="w-full max-w-3xl space-y-1.5">
      <div class="flex items-center space-x-2 text-xs text-gray-400">
        <div class="w-5 h-5 rounded-md bg-gradient-to-tr from-emerald-500 to-indigo-600 flex items-center justify-center text-[10px] font-bold text-white">K2</div>
        <span class="font-medium text-gray-300">K2 Horizon AI</span>
      </div>
      
      <!-- Isolated Live Thinking Box -->
      <details class="thinking-box active thinking-box-${msgUid}" open>
        <summary class="thinking-header">
          <span class="flex items-center space-x-1.5">
            <i data-lucide="sparkles" class="w-3.5 h-3.5 text-emerald-400 animate-spin"></i>
            <span class="thinking-title-${msgUid}">Evaluating prompt...</span>
          </span>
          <span class="text-[10px] text-emerald-400 font-mono thinking-timer-${msgUid}">0.0s</span>
        </summary>
        <div class="thinking-content thinking-text-${msgUid}"></div>
      </details>

      <!-- Isolated Live Answer -->
      <div class="prose text-gray-100 text-xs sm:text-sm leading-relaxed answer-text-${msgUid}"></div>

      <!-- Isolated Live Metrics Card -->
      <div class="metrics-card-${msgUid} hidden"></div>

      <!-- Live Actions -->
      <div class="msg-actions live-actions-${msgUid} hidden"></div>
    </div>
  `;
  messagesListEl.appendChild(assistantBubble);
  refreshIcons();
  scrollToBottom();

  const liveThinkingBox = assistantBubble.querySelector(`.thinking-box-${msgUid}`);
  const liveThinkingTitle = assistantBubble.querySelector(`.thinking-title-${msgUid}`);
  const liveThinkingTimer = assistantBubble.querySelector(`.thinking-timer-${msgUid}`);
  const liveThinkingText = assistantBubble.querySelector(`.thinking-text-${msgUid}`);
  const liveAnswerText = assistantBubble.querySelector(`.answer-text-${msgUid}`);
  const liveMetricsCard = assistantBubble.querySelector(`.metrics-card-${msgUid}`);
  const liveActions = assistantBubble.querySelector(`.live-actions-${msgUid}`);

  liveSpeedometerEl.classList.remove("hidden");
  liveSpeedometerEl.classList.add("flex");

  let streamStartTime = Date.now();
  let tokenCount = 0;
  let accumulatedThink = "";
  let accumulatedAnswer = "";

  const timerInterval = setInterval(() => {
    const elapsed = ((Date.now() - streamStartTime) / 1000).toFixed(1);
    liveTimeValEl.textContent = `${elapsed}s`;
    if (liveThinkingTimer) liveThinkingTimer.textContent = `${elapsed}s`;
    if (tokenCount > 0 && elapsed > 0) {
      const speed = (tokenCount / elapsed).toFixed(1);
      liveSpeedValEl.textContent = `${speed} tok/s`;
    }
  }, 100);

  currentEventSource = new EventSource(`/api/chats/${currentChatId}/stream`);

  currentEventSource.addEventListener("status", (e) => {
    const data = JSON.parse(e.data);
    if (liveThinkingTitle) liveThinkingTitle.textContent = data.message;
    footerStatusEl.textContent = data.message;
  });

  currentEventSource.addEventListener("ttft", (e) => {
    const data = JSON.parse(e.data);
    liveSpeedValEl.textContent = `TTFT: ${data.ttft_ms}ms`;
    if (liveThinkingTitle) liveThinkingTitle.textContent = "Reasoning step-by-step...";
    footerStatusEl.textContent = "Streaming tokens...";
  });

  currentEventSource.addEventListener("think", (e) => {
    const data = JSON.parse(e.data);
    accumulatedThink += data.chunk;
    if (liveThinkingText) liveThinkingText.textContent = cleanSpecial(accumulatedThink);
    tokenCount += 1;
    scrollToBottom();
  });

  currentEventSource.addEventListener("think_end", (e) => {
    const data = JSON.parse(e.data);
    if (liveThinkingBox) {
      liveThinkingBox.classList.remove("active");
      liveThinkingBox.removeAttribute("open");
    }
    if (liveThinkingTitle) liveThinkingTitle.textContent = `Thought process complete (${data.duration}s)`;
  });

  currentEventSource.addEventListener("answer", (e) => {
    const data = JSON.parse(e.data);
    accumulatedAnswer += data.chunk;
    if (liveAnswerText) liveAnswerText.innerHTML = marked.parse(cleanSpecial(accumulatedAnswer));
    tokenCount += 1;
    scrollToBottom();
  });

  currentEventSource.addEventListener("metrics", (e) => {
    const m = JSON.parse(e.data);
    if (liveMetricsCard) {
      const quant = m.quantization || "4-Bit Q4";
      liveMetricsCard.className = "latency-card";
      liveMetricsCard.innerHTML = `
        <div class="latency-item">
          <span>⏱️ TTFT:</span>
          <strong class="text-emerald-400">${m.ttft_ms}ms</strong>
        </div>
        <span class="text-gray-600">&bull;</span>
        <div class="latency-item">
          <span>⚡ Speed:</span>
          <strong class="text-emerald-400">${m.tok_per_sec} tok/s</strong>
        </div>
        <span class="text-gray-600">&bull;</span>
        <div class="latency-item">
          <span>🕒 Total:</span>
          <strong>${m.total_time_s}s</strong>
        </div>
        <span class="text-gray-600">&bull;</span>
        <div class="latency-item">
          <span class="text-emerald-400/90 font-mono">${quant}</span>
        </div>
      `;
      liveMetricsCard.classList.remove("hidden");
    }
  });

  currentEventSource.addEventListener("done", () => {
    cleanup();
  });

  currentEventSource.addEventListener("error", (e) => {
    console.error("SSE Error:", e);
    cleanup();
  });

  function cleanup() {
    clearInterval(timerInterval);
    if (currentEventSource) {
      currentEventSource.close();
      currentEventSource = null;
    }
    isGenerating = false;
    sendBtnEl.classList.remove("hidden");
    stopGenBtn.classList.add("hidden");
    liveSpeedometerEl.classList.add("hidden");
    liveSpeedometerEl.classList.remove("flex");
    footerStatusEl.textContent = "Ready";

    if (liveActions) {
      const cleanAns = cleanSpecial(accumulatedAnswer);
      liveActions.className = "msg-actions";
      liveActions.innerHTML = `
        <button onclick="copyText('${escapeForJs(cleanAns)}')" class="action-btn" title="Copy response">
          <i data-lucide="copy" class="w-3.5 h-3.5"></i>
        </button>
        <button onclick="toggleTTS('${escapeForJs(cleanAns)}', this)" class="action-btn" title="Read Aloud (Voice)">
          <i data-lucide="volume-2" class="w-3.5 h-3.5"></i>
        </button>
      `;
      refreshIcons();
    }

    attachCopyButtons();
    loadChats();
  }
});

// Stop Generation Handler
stopGenBtn.addEventListener("click", async () => {
  if (currentChatId) {
    try {
      await fetch(`/api/chats/${currentChatId}/abort`, { method: "POST" });
    } catch (err) {}
  }
  if (currentEventSource) {
    currentEventSource.close();
    currentEventSource = null;
  }
  isGenerating = false;
  sendBtnEl.classList.remove("hidden");
  stopGenBtn.classList.add("hidden");
  liveSpeedometerEl.classList.add("hidden");
  liveSpeedometerEl.classList.remove("flex");
  footerStatusEl.textContent = "Generation stopped";
});

// ---------------- Message Actions Helpers ----------------
window.copyText = function(text) {
  navigator.clipboard.writeText(text);
  footerStatusEl.textContent = "Copied to clipboard!";
  setTimeout(() => { footerStatusEl.textContent = "Ready"; }, 1500);
};

window.regenerateResponse = async function(messageId) {
  if (isGenerating || !currentChatId) return;
  try {
    await fetch(`/api/chats/${currentChatId}/regenerate/${messageId}`, { method: "POST" });
    await selectChat(currentChatId);
    chatFormEl.dispatchEvent(new Event("submit"));
  } catch (err) {
    console.error("Regenerate failed:", err);
  }
};

window.editPrompt = function(btnElement, messageId) {
  const card = btnElement.closest(".message-card");
  const textDiv = card.querySelector(".whitespace-pre-wrap");
  if (!textDiv) return;

  const currentText = textDiv.innerText;
  userInputEl.value = currentText;
  userInputEl.focus();
  userInputEl.style.height = "auto";
  userInputEl.style.height = Math.min(userInputEl.scrollHeight, 180) + "px";
};

// ---------------- Prompt Starters & Model Cards ----------------
document.querySelectorAll(".starter-chip").forEach(chip => {
  chip.addEventListener("click", () => {
    const prompt = chip.getAttribute("data-prompt");
    userInputEl.value = prompt;
    userInputEl.style.height = "auto";
    userInputEl.style.height = Math.min(userInputEl.scrollHeight, 180) + "px";
    chatFormEl.dispatchEvent(new Event("submit"));
  });
});

document.querySelectorAll("[data-choose-model]").forEach(card => {
  card.addEventListener("click", () => {
    const model = card.getAttribute("data-choose-model");
    const persona = personaSelectEl ? personaSelectEl.value : "default";
    createNewChat(model, persona);
  });
});

newChatBtn.addEventListener("click", () => {
  showLandingView();
});

document.querySelectorAll(".filter-tab").forEach(tab => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".filter-tab").forEach(t => {
      t.classList.remove("active", "bg-dark-800", "text-white");
      t.classList.add("text-gray-400");
    });
    tab.classList.add("active", "bg-dark-800", "text-white");
    tab.classList.remove("text-gray-400");
    activeFilter = tab.getAttribute("data-filter");
    renderChatList();
  });
});

function scrollToBottom() {
  chatContainerEl.scrollTop = chatContainerEl.scrollHeight;
}

function escapeHtml(text) {
  const map = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' };
  return (text || '').replace(/[&<>"']/g, m => map[m]);
}

function escapeForJs(text) {
  return (text || '').replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '');
}

function cleanSpecial(text) {
  const tokens = ["<|ifm|im_end|>", "<|im_end|>", "<|endoftext|>", "<|im_start|>", "<s>", "</s>", "<ifm|think>", "</ifm|think>", "<think>", "</think>"];
  let res = text || "";
  tokens.forEach(t => {
    res = res.replaceAll(t, "");
  });
  return res;
}

function attachCopyButtons() {
  document.querySelectorAll("pre").forEach(pre => {
    if (pre.querySelector(".copy-code-btn")) return;
    const btn = document.createElement("button");
    btn.className = "copy-code-btn";
    btn.textContent = "Copy";
    btn.onclick = () => {
      const code = pre.querySelector("code")?.innerText || pre.innerText;
      navigator.clipboard.writeText(code);
      btn.textContent = "Copied!";
      setTimeout(() => btn.textContent = "Copy", 1500);
    };
    pre.appendChild(btn);
  });
}

// Initial Load
loadChats();

