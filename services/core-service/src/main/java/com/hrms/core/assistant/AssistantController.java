package com.hrms.core.assistant;

import com.hrms.core.assistant.AiClient.AskResponse;
import com.hrms.core.assistant.AiClient.UploadDocumentRequest;
import com.hrms.core.assistant.AiClient.UploadDocumentResponse;
import com.hrms.core.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR policy assistant. The browser only ever talks to this API; the AI service stays internal.
 *
 * <p>Any authenticated employee may ask; the AI service decides which documents they're allowed
 * to see. Only HR admins may add documents, which is checked here AND again in the AI service
 * (defense in depth). RateLimitFilter caps calls per user (cost control).
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    public record QuestionRequest(@NotBlank @Size(max = 2000) String question) {}

    public record AddDocumentRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200_000) String content,
            @NotEmpty Set<Role> allowedRoles) {}

    private final AiClient aiClient;

    public AssistantController(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody QuestionRequest request) {
        return aiClient.ask(request.question());
    }

    @PostMapping("/documents")
    @PreAuthorize("hasRole('HR_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadDocumentResponse addDocument(@Valid @RequestBody AddDocumentRequest request) {
        return aiClient.uploadDocument(
                new UploadDocumentRequest(request.title(), request.content(), request.allowedRoles()));
    }
}
