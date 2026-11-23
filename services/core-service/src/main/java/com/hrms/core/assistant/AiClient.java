package com.hrms.core.assistant;

import com.hrms.core.config.HrmsProperties;
import com.hrms.core.security.CurrentUser;
import com.hrms.core.security.Role;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the Python AI service.
 *
 * <p><b>Token relay:</b> we forward the user's own access token instead of using a powerful
 * service account. The AI service verifies it independently and only searches documents
 * that this user's tenant and roles allow (zero trust between services).
 *
 * <p>Timeouts are explicit: an HTTP call without a timeout can hang a thread forever.
 */
@Component
public class AiClient {

    public record AskRequest(String question) {}

    public record Source(UUID documentId, String title, double score) {}

    public record AskResponse(String answer, List<Source> sources, String model) {}

    public record UploadDocumentRequest(String title, String content, Set<Role> allowedRoles) {}

    public record UploadDocumentResponse(UUID documentId, int chunks) {}

    private final RestClient rest;

    public AiClient(RestClient.Builder builder, HrmsProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                // The JDK client defaults to HTTP/2 and, on plain http://, sends an "h2c" upgrade
                // request that Uvicorn rejects (400/422). Internal calls stay on HTTP/1.1.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.ai().connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.ai().readTimeout());

        this.rest = builder
                .baseUrl(props.ai().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public AskResponse ask(String question) {
        return rest.post()
                .uri("/v1/ask")
                .headers(headers -> headers.setBearerAuth(CurrentUser.bearerToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AskRequest(question))
                .retrieve()
                .body(AskResponse.class);
    }

    public UploadDocumentResponse uploadDocument(UploadDocumentRequest request) {
        return rest.post()
                .uri("/v1/documents")
                .headers(headers -> headers.setBearerAuth(CurrentUser.bearerToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(UploadDocumentResponse.class);
    }
}
