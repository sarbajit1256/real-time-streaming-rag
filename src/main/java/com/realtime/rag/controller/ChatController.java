/*package com.realtime.rag.controller;

import com.realtime.rag.dto.ChatRequest;
import com.realtime.rag.service.RagChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final RagChatService chatService;
    public ChatController(RagChatService chatService) { this.chatService = chatService; }

    @PostMapping
    public RagChatService.ChatResult chat(@Valid @RequestBody ChatRequest request) {
        return chatService.ask(request.question(), request.asOf());
    }
}
*/



package com.realtime.rag.controller;

import com.realtime.rag.dto.ChatRequest;
import com.realtime.rag.service.RagChatService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagChatService chatService;

    public ChatController(RagChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public RagChatService.ChatResult chat(
            @Valid @RequestBody ChatRequest request) {

        return chatService.ask(
                request.question(),
                request.asOf(),
                request.selectedDocumentId()
        );
    }
}
