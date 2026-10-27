const byId = (id) => document.getElementById(id);

// --------------------------------------------------
// DATE HELPERS
// --------------------------------------------------

function toPickerValue(date = new Date()) {
    const pad = (n) => String(n).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function pickerToIsoUtc(pickerValue) {
    if (!pickerValue) return null;
    const parsed = new Date(pickerValue);
    return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

function showStatus(id, message, error = false) {
    const el = byId(id);
    if (!el) return;
    el.textContent = message;
    el.classList.toggle("error", error);
}

function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[m]);
}

// --------------------------------------------------
// MULTI-TURN CONVERSATION THREAD STORE (WITH 3-DOTS MENU)
// --------------------------------------------------

const CONVERSATIONS_KEY = "streaming_rag_history_v1";
let activeSessionId = null;
let activeOpenMenuId = null;

function getStoredConversations() {
    try {
        return JSON.parse(localStorage.getItem(CONVERSATIONS_KEY) || "[]");
    } catch {
        return [];
    }
}

function saveSortedConversations(list) {
    list.sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0));
    localStorage.setItem(CONVERSATIONS_KEY, JSON.stringify(list.slice(0, 35)));
}

function saveOrUpdateConversation(question, answerRaw, sources) {
    const list = getStoredConversations();
    let session = list.find(s => s.id === activeSessionId);

    if (!session) {
        activeSessionId = crypto.randomUUID();
        session = {
            id: activeSessionId,
            title: question.trim().length > 30 ? question.trim().substring(0, 30) + "..." : question.trim(),
            pinned: false,
            messages: [],
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
        };
        list.unshift(session);
    }

    session.messages.push({
        question: question,
        answer: answerRaw,
        sources: sources || []
    });

    saveSortedConversations(list);
    renderConversationHistoryList();
}

function togglePinConversation(id, e) {
    if (e) {
        e.stopPropagation();
        e.preventDefault();
    }
    let list = getStoredConversations();
    const session = list.find(item => item.id === id);
    if (!session) return;

    session.pinned = !session.pinned;
    saveSortedConversations(list);
    closeAllHistoryMenus();
    renderConversationHistoryList();
}

function renameConversation(id, e) {
    if (e) {
        e.stopPropagation();
        e.preventDefault();
    }
    let list = getStoredConversations();
    const session = list.find(item => item.id === id);
    if (!session) return;

    closeAllHistoryMenus();
    const newTitle = prompt("Rename conversation:", session.title);
    if (newTitle && newTitle.trim()) {
        session.title = newTitle.trim();
        saveSortedConversations(list);
        renderConversationHistoryList();
    }
}

function deleteConversationSession(id, e) {
    if (e) {
        e.stopPropagation();
        e.preventDefault();
        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
    }

    const rowEl = document.querySelector(`.history-item[data-id="${id}"]`);
    if (rowEl) {
        rowEl.remove();
    }

    let list = getStoredConversations();
    list = list.filter(item => String(item.id) !== String(id));
    saveSortedConversations(list);

    closeAllHistoryMenus();

    const container = byId("conversationHistoryList");
    if (container && list.length === 0) {
        container.innerHTML = '<div class="history-empty">No previous sessions yet</div>';
    }

    if (activeSessionId === id) {
        resetToNewChat();
    }
}

function restoreConversationSession(id) {
    const list = getStoredConversations();
    const session = list.find(s => s.id === id);
    if (!session) return;

    activeSessionId = id;
    byId("welcomeBox")?.remove();
    chatThread.replaceChildren();

    let firstRow = null;
    session.messages.forEach((turn, idx) => {
        const uRow = appendUserMessage(turn.question);
        if (idx === 0) firstRow = uRow;
        const botRow = appendBotPlaceholder();
        renderBotResponse(botRow, { answer: turn.answer, sources: turn.sources }, true);
    });

    document.querySelectorAll(".history-item").forEach(item => {
        item.classList.toggle("active", item.dataset.id === id);
    });

    if (firstRow) {
        scrollToRecentExchange(firstRow);
    }
}

function closeAllHistoryMenus() {
    document.querySelectorAll(".history-dropdown-menu").forEach(el => el.remove());
    document.querySelectorAll(".history-item").forEach(el => el.classList.remove("menu-open"));
    activeOpenMenuId = null;
}

function toggleHistoryMenu(id, e) {
    if (e) {
        e.stopPropagation();
        e.preventDefault();
    }

    if (activeOpenMenuId === id) {
        closeAllHistoryMenus();
        return;
    }

    closeAllHistoryMenus();
    activeOpenMenuId = id;

    const row = document.querySelector(`.history-item[data-id="${id}"]`);
    if (!row) return;

    row.classList.add("menu-open");

    const list = getStoredConversations();
    const session = list.find(s => s.id === id);
    const isPinned = session && session.pinned;

    const menu = document.createElement("div");
    menu.className = "history-dropdown-menu";
    menu.innerHTML = `
        <button type="button" class="history-menu-option" id="menuPinBtn">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="17" x2="12" y2="22"></line>
                <path d="M5 17h14v-2l-2-2V5a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v8l-2 2v2z"></path>
            </svg>
            <span>${isPinned ? 'Unpin' : 'Pin'}</span>
        </button>
        <button type="button" class="history-menu-option" id="menuRenameBtn">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
                <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"></path>
                <path d="m15 5 4 4"></path>
            </svg>
            <span>Rename</span>
        </button>
        <button type="button" class="history-menu-option danger" id="menuDeleteBtn">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
                <path d="M3 6h18"></path>
                <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"></path>
                <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"></path>
                <line x1="10" y1="11" x2="10" y2="17"></line>
                <line x1="14" y1="11" x2="14" y2="17"></line>
            </svg>
            <span>Delete</span>
        </button>
    `;

    menu.querySelector("#menuPinBtn").onclick = (ev) => {
        ev.stopPropagation();
        ev.preventDefault();
        togglePinConversation(id, ev);
    };

    menu.querySelector("#menuRenameBtn").onclick = (ev) => {
        ev.stopPropagation();
        ev.preventDefault();
        renameConversation(id, ev);
    };

    menu.querySelector("#menuDeleteBtn").onclick = (ev) => {
        ev.stopPropagation();
        ev.preventDefault();
        deleteConversationSession(id, ev);
    };

    row.appendChild(menu);
}

function renderConversationHistoryList() {
    const container = byId("conversationHistoryList");
    if (!container) return;

    const list = getStoredConversations();
    if (list.length === 0) {
        container.innerHTML = '<div class="history-empty">No previous sessions yet</div>';
        return;
    }

    container.replaceChildren();
    list.forEach(item => {
        const row = document.createElement("div");
        row.className = "history-item" + 
            (item.id === activeSessionId ? " active" : "") + 
            (item.pinned ? " is-pinned" : "");
        row.dataset.id = item.id;

        const pinSvg = `
            <span class="pin-indicator" title="Pinned">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="17" x2="12" y2="22"></line>
                    <path d="M5 17h14v-2l-2-2V5a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v8l-2 2v2z"></path>
                </svg>
            </span>
        `;

        row.innerHTML = `
            <div class="history-item-left">
                ${item.pinned ? pinSvg : ''}
                <span class="history-item-title" title="${escapeHtml(item.title)}">${escapeHtml(item.title)}</span>
            </div>
            <button type="button" class="history-menu-btn" title="Options">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                    <circle cx="5" cy="12" r="2"></circle>
                    <circle cx="12" cy="12" r="2"></circle>
                    <circle cx="19" cy="12" r="2"></circle>
                </svg>
            </button>
        `;

        row.onclick = () => {
            closeAllHistoryMenus();
            restoreConversationSession(item.id);
        };

        const dotsBtn = row.querySelector(".history-menu-btn");
        dotsBtn.onclick = (e) => toggleHistoryMenu(item.id, e);

        container.appendChild(row);
    });
}

function resetToNewChat() {
    activeSessionId = null;
    closeAllHistoryMenus();
    chatThread.innerHTML = `
        <div class="welcome-box" id="welcomeBox">
            <div class="welcome-avatar">⚡</div>
            <h2>What would you like to verify today?</h2>
            <p>Ask time-sensitive questions across your ingested PDFs (digital and scanned OCR documents). Supplemental facts are dynamically reconciled in real-time.</p>
        </div>
    `;
    document.querySelectorAll(".history-item").forEach(item => item.classList.remove("active"));
    clearDocumentFilter();
}

// --------------------------------------------------
// FILTER SCOPE SYNCHRONIZATION
// --------------------------------------------------

let availablePdfDocuments = [];

function setDocumentFilter(docId, docTitle) {
    const hiddenDocId = byId("chatDocId");
    const activeChip = byId("filterSelectedChip");
    const chipTitle = byId("filterSelectedTitle");
    const searchInput = byId("chatDocSearchInput");
    const scopeBadge = byId("chatScopeBadge");
    const scopeText = byId("chatScopeText");
    const clearScopeBtn = byId("clearScopeBadgeBtn");

    if (docId) {
        hiddenDocId.value = docId;
        if (chipTitle) chipTitle.textContent = docTitle || "Document";
        if (activeChip) activeChip.style.display = "inline-flex";
        if (searchInput) {
            searchInput.style.display = "none";
            searchInput.value = "";
        }
        if (scopeBadge) scopeBadge.classList.add("is-filtered");
        if (scopeText) scopeText.textContent = `Filtered: ${docTitle || docId}`;
        if (clearScopeBtn) clearScopeBtn.style.display = "inline-block";
    } else {
        clearDocumentFilter();
    }
}

function clearDocumentFilter() {
    const hiddenDocId = byId("chatDocId");
    const activeChip = byId("filterSelectedChip");
    const searchInput = byId("chatDocSearchInput");
    const scopeBadge = byId("chatScopeBadge");
    const scopeText = byId("chatScopeText");
    const clearScopeBtn = byId("clearScopeBadgeBtn");

    if (hiddenDocId) hiddenDocId.value = "";
    if (activeChip) activeChip.style.display = "none";
    if (searchInput) {
        searchInput.style.display = "inline-block";
        searchInput.value = "";
    }
    if (scopeBadge) scopeBadge.classList.remove("is-filtered");
    if (scopeText) scopeText.textContent = "All Documents (Unfiltered)";
    if (clearScopeBtn) clearScopeBtn.style.display = "none";
}

// --------------------------------------------------
// AUTOCOMPLETE & DIRECTORY POPULATION
// --------------------------------------------------

async function populateDocumentAutocomplete() {
    const datalist = byId("documentDatalist");
    const activeDocList = byId("activeDocList");
    if (!datalist) return;

    try {
        const response = await fetch("/api/knowledge/documents");
        if (!response.ok) return;

        availablePdfDocuments = await response.json();
        datalist.replaceChildren();
        if (activeDocList) activeDocList.replaceChildren();

        availablePdfDocuments.forEach((doc) => {
            const option = document.createElement("option");
            option.value = `${doc.title || "Untitled PDF"} (${doc.documentId})`;
            option.dataset.id = doc.documentId;
            datalist.appendChild(option);

            if (activeDocList) {
                const item = document.createElement("div");
                item.className = "doc-item-row";
                item.innerHTML = `<span>📄 ${escapeHtml(doc.title || "Document")}</span> <small style="color:var(--text-muted)">v${doc.version}</small>`;
                item.onclick = () => {
                    setDocumentFilter(doc.documentId, doc.title);
                };
                activeDocList.appendChild(item);
            }
        });
    } catch (err) {
        console.error("Could not load documents:", err);
    }
}

// --------------------------------------------------
// PDF UPLOAD (WITH IN-MEMORY INSPECTION & REPLACEMENT)
// --------------------------------------------------

const uploadForm = byId("uploadForm");
if (uploadForm) {
    uploadForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const fileInput = byId("pdfFile");
        const files = Array.from(fileInput?.files || []);
        if (files.length === 0) return;

        const button = uploadForm.querySelector('button[type="submit"]');
        button.disabled = true;
        button.textContent = "Inspecting (OCR check)...";
        showStatus("uploadStatus", "Inspecting PDF contents (OCR ready for scanned documents)...");

        const verifiedEntries = [];

        try {
            for (const file of files) {
                const inspectData = new FormData();
                inspectData.append("file", file);

                const inspectRes = await fetch("/api/knowledge/pdf/inspect", {
                    method: "POST",
                    body: inspectData
                });
                const inspection = await inspectRes.json();

                if (inspection.status === "CONFLICT_DIFFERENT_CONTENT") {
                    showStatus("uploadStatus", `Blocked: "${file.name}" exists with different content. Rename file.`, true);
                    return;
                }

                let replaceDocumentId = null;
                let nextVersion = null;

                if (inspection.status === "EXACT_DUPLICATE") {
                    if (!confirm(`"${file.name}" is already indexed with identical content.\n\nDo you want to replace the existing indexed document?`)) {
                        showStatus("uploadStatus", `Upload canceled for "${file.name}".`);
                        return;
                    }
                    replaceDocumentId = inspection.existingDocumentId;
                    nextVersion = (inspection.existingVersion || 1) + 1;
                }

                if (inspection.status === "REVISION_PAGE_CHANGES") {
                    if (!confirm(`"${file.name}" has page revisions (${inspection.similarityPercentage}% match).\n\nDo you want to update and replace the existing document?`)) {
                        showStatus("uploadStatus", `Update canceled for "${file.name}".`);
                        return;
                    }
                    replaceDocumentId = inspection.existingDocumentId;
                    nextVersion = (inspection.existingVersion || 1) + 1;
                }

                verifiedEntries.push({ file, replaceDocumentId, nextVersion });
            }

            button.textContent = "Uploading...";

            for (const entry of verifiedEntries) {
                const formData = new FormData();
                formData.append("files", entry.file, entry.file.name);
                if (entry.replaceDocumentId) {
                    formData.append("replaceDocumentId", entry.replaceDocumentId);
                }
                if (entry.nextVersion) {
                    formData.append("version", entry.nextVersion);
                }

                const response = await fetch("/api/knowledge/pdf", { method: "POST", body: formData });
                const result = await response.json();
                showStatus("uploadStatus", result.message || "PDF queued for ingestion.");
            }

            uploadForm.reset();
            byId("fileSelectionList")?.replaceChildren();
            setTimeout(populateDocumentAutocomplete, 2000);

        } catch (err) {
            showStatus("uploadStatus", err.message || "Upload error.", true);
        } finally {
            button.disabled = false;
            button.textContent = "Upload & Index";
        }
    });
}

// --------------------------------------------------
// MANUAL SUPPLEMENTAL FACT PUBLISHING
// --------------------------------------------------

const eventForm = byId("eventForm");
if (eventForm) {
    eventForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const form = new FormData(eventForm);
        const button = eventForm.querySelector('button[type="submit"]');
        button.disabled = true;

        try {
            let docId = String(form.get("documentId") || "").trim();
            const match = docId.match(/\(([a-f0-9\-]+)\)$/i);
            if (match) docId = match[1];

            const payload = {
                eventId: crypto.randomUUID(),
                documentId: docId,
                title: form.get("title"),
                content: form.get("content"),
                version: 1,
                validFrom: pickerToIsoUtc(form.get("validFrom")),
                validTo: pickerToIsoUtc(form.get("validTo")),
                operation: "UPSERT",
                source: "manual-patch.txt",
                eventType: "SUPPLEMENTAL_FACT",
                factKey: form.get("factKey"),
                priority: form.get("priority") || "HIGH",
                supersedesEventId: null
            };

            const response = await fetch("/api/knowledge/events", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });

            const res = await response.json();
            showStatus("eventStatus", res.message || "Supplemental fact published to Kafka.");
            eventForm.reset();
            const validFrom = byId("validFromPicker");
            if (validFrom && !validFrom.value) validFrom.value = toPickerValue(new Date());

        } catch (err) {
            showStatus("eventStatus", err.message || "Fact publishing failed.", true);
        } finally {
            button.disabled = false;
        }
    });
}

// --------------------------------------------------
// CHAT MESSAGE THREAD
// --------------------------------------------------

const chatForm = byId("chatForm");
const chatThread = byId("chatThread");
const chatQuestion = byId("chatQuestion");

if (chatQuestion) {
    chatQuestion.addEventListener("input", function() {
        this.style.height = "auto";
        this.style.height = Math.min(this.scrollHeight, 180) + "px";
    });

    chatQuestion.addEventListener("keydown", function(e) {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            chatForm.dispatchEvent(new Event("submit"));
        }
    });
}

if (chatForm) {
    chatForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const question = chatQuestion.value.trim();
        if (!question) return;

        byId("welcomeBox")?.remove();

        const userRow = appendUserMessage(question);
        chatQuestion.value = "";
        chatQuestion.style.height = "auto";

        const botBubble = appendBotPlaceholder();
        scrollToRecentExchange(userRow);

        const form = new FormData(chatForm);
        const docId = String(form.get("documentId") || "").trim();

        const payload = {
            question,
            asOf: pickerToIsoUtc(form.get("asOf")),
            documentId: docId || null
        };

        try {
            const response = await fetch("/api/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });

            const result = await response.json();
            renderBotResponse(botBubble, result);

            saveOrUpdateConversation(question, result.answer || "", result.sources || []);

        } catch (err) {
            botBubble.querySelector(".bot-content").textContent = "Error: " + err.message;
        } finally {
            scrollToRecentExchange(userRow);
        }
    });
}

function appendUserMessage(text) {
    const row = document.createElement("div");
    row.className = "chat-row user-row";
    row.innerHTML = `
        <div class="chat-bubble">${escapeHtml(text)}</div>
        <div class="chat-avatar user-avatar">U</div>
    `;
    chatThread.appendChild(row);
    return row;
}

function appendBotPlaceholder() {
    const row = document.createElement("div");
    row.className = "chat-row bot-row";
    row.innerHTML = `
        <div class="chat-avatar bot-avatar">R</div>
        <div class="chat-bubble">
            <div class="bot-content" style="color:var(--text-muted)">Synthesizing answer from verified evidence...</div>
        </div>
    `;
    chatThread.appendChild(row);
    return row;
}

/**
 * Strips out any internal trailing citation notes or metadata.
 */
function cleanDirectAnswer(rawAnswer) {
    if (!rawAnswer) return "No response received.";

    const cutoffRegex = /(?:---+\s*)?(?:Source(?:s)?:\s*|Supporting details from earlier passages|Earlier passages|Historical context|Supplemental evidence)[\s\S]*/i;
    const match = rawAnswer.match(cutoffRegex);

    if (match && match.index > 0) {
        return rawAnswer.substring(0, match.index).trim();
    }

    return rawAnswer.trim();
}

function renderBotResponse(row, result, isHistorical = false) {
    const bubble = row.querySelector(".chat-bubble");
    bubble.innerHTML = "";

    // 1. Clean Top Bar
    const topBar = document.createElement("div");
    topBar.className = "response-top-bar";
    topBar.innerHTML = `<span class="response-meta-tag">${isHistorical ? 'Archived Session' : 'Verified Synthesis'}</span>`;

    // 2. Identify the target document ID (either from filtered input OR retrieved sources)
    const sources = Array.isArray(result.sources) ? result.sources : [];
    const sourceDocId = sources.find(s => s.documentId && s.documentId !== "unknown")?.documentId;
    const filterDocId = byId("chatDocId")?.value;
    const targetDocId = filterDocId || sourceDocId;

    // 3. Render the "+ Patch Fact" button whenever a document ID is present
    if (targetDocId) {
        const patchBtn = document.createElement("button");
        patchBtn.type = "button";
        patchBtn.className = "btn btn-ghost btn-sm";
        patchBtn.innerHTML = "⚡ + Patch Fact";
        patchBtn.onclick = () => {
            const docInput = byId("eventDocumentId");
            if (docInput) {
                docInput.value = targetDocId;
                byId("sidebar")?.classList.remove("collapsed");
                byId("factKey")?.focus();
            }
        };
        topBar.appendChild(patchBtn);
    }
    bubble.appendChild(topBar);

    // 4. Render clean answer with line breaks enabled
    const exactAnswer = cleanDirectAnswer(result.answer || "");
    const contentBox = document.createElement("div");
    contentBox.className = "markdown-body";

    if (window.marked) {
        marked.setOptions({
            breaks: true, // Converts single \n newlines to <br> line breaks
            gfm: true
        });
    }

    contentBox.innerHTML = window.marked && window.DOMPurify 
        ? DOMPurify.sanitize(marked.parse(exactAnswer)) 
        : escapeHtml(exactAnswer);

    bubble.appendChild(contentBox);
}

/**
 * Positions viewport cleanly at the start of the user question
 */
function scrollToRecentExchange(targetElement) {
    if (!targetElement) return;
    targetElement.scrollIntoView({ behavior: "smooth", block: "start" });
}

// --------------------------------------------------
// INITIALIZATION & EVENT LISTENERS
// --------------------------------------------------

document.addEventListener("DOMContentLoaded", () => {
    const sidebar = byId("sidebar");

    const toggleSidebar = () => {
        sidebar?.classList.toggle("collapsed");
    };

    byId("sidebarCloseBtn")?.addEventListener("click", toggleSidebar);
    byId("navbarSidebarToggle")?.addEventListener("click", toggleSidebar);

    byId("clearChatBtn")?.addEventListener("click", resetToNewChat);
    byId("newChatSidebarBtn")?.addEventListener("click", resetToNewChat);

    byId("clearScopeBadgeBtn")?.addEventListener("click", (e) => {
        e.stopPropagation();
        clearDocumentFilter();
    });

    byId("clearSelectedChipBtn")?.addEventListener("click", (e) => {
        e.stopPropagation();
        clearDocumentFilter();
    });

    byId("chatScopeBadge")?.addEventListener("click", () => {
        const hiddenDocId = byId("chatDocId");
        if (hiddenDocId && hiddenDocId.value) {
            clearDocumentFilter();
        }
    });

    byId("chatDocSearchInput")?.addEventListener("input", (e) => {
        const val = e.target.value.trim();
        if (!val) return;

        const match = val.match(/^(.*?)\s*\(([a-f0-9\-]+)\)$/i);
        if (match) {
            setDocumentFilter(match[2].trim(), match[1].trim());
            return;
        }

        const doc = availablePdfDocuments.find(d => 
            (d.title && d.title.toLowerCase() === val.toLowerCase()) || d.documentId === val
        );
        if (doc) {
            setDocumentFilter(doc.documentId, doc.title);
        }
    });

    byId("pdfFile")?.addEventListener("change", (e) => {
        const list = byId("fileSelectionList");
        list.replaceChildren();
        Array.from(e.target.files).forEach(f => {
            const badge = document.createElement("div");
            badge.className = "file-item-pill";
            badge.textContent = `📄 ${f.name} (${(f.size / 1024 / 1024).toFixed(1)} MB)`;
            list.appendChild(badge);
        });
    });

    // Dismiss context menus when clicking outside
    document.addEventListener("click", (e) => {
        if (!e.target.closest(".history-dropdown-menu") && !e.target.closest(".history-menu-btn")) {
            closeAllHistoryMenus();
        }
    });

    const validFrom = byId("validFromPicker");
    if (validFrom && !validFrom.value) validFrom.value = toPickerValue(new Date());
	
	
	// --------------------------------------------------
	// CLEAR ALL INDEXED DOCUMENTS
	// --------------------------------------------------

	byId("clearAllDocumentsBtn")?.addEventListener("click", async () => {

	    const confirmed = confirm(
	        "Are you sure you want to delete all indexed documents and supplemental facts?\n\n" +
	        "This action cannot be undone.\n\n" +
	        "Uploaded PDF files, Kafka messages, and chat history will not be deleted."
	    );

	    if (!confirmed) {
	        return;
	    }

	    const button = byId("clearAllDocumentsBtn");

	    button.disabled = true;
	    button.textContent = "Clearing...";

	    try {
	        const response = await fetch(
	            "/api/knowledge/documents/clear-all",
	            {
	                method: "POST"
	            }
	        );

	        const result = await response.json();

	        if (!response.ok || result.success !== true) {
	            throw new Error(
	                result.message || "Failed to clear indexed documents."
	            );
	        }

	        // Remove any active document filter.
	        clearDocumentFilter();

	        // Refresh the sidebar and autocomplete list.
	        await populateDocumentAutocomplete();

	        showStatus(
	            "uploadStatus",
	            result.message || "Knowledge base cleared successfully."
	        );

	    } catch (error) {
	        console.error("Clear-all operation failed:", error);

	        showStatus(
	            "uploadStatus",
	            error.message || "Could not clear the knowledge base.",
	            true
	        );

	    } finally {
	        button.disabled = false;
	        button.textContent = "🗑️ Clear All Documents";
	    }
	});


    populateDocumentAutocomplete();
    renderConversationHistoryList();
});