package com.hrms.core.assistant;

import static com.hrms.core.TestUsers.newTenant;
import static com.hrms.core.TestUsers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.core.TestcontainersConfiguration;
import com.hrms.core.assistant.AiClient.AskResponse;
import com.hrms.core.assistant.AiClient.Source;
import com.hrms.core.assistant.AiClient.UploadDocumentRequest;
import com.hrms.core.assistant.AiClient.UploadDocumentResponse;
import com.hrms.core.security.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The AI service is replaced by a Mockito mock ({@code @MockitoBean}), so these tests check
 * only this service's rules: who may call what, and what gets forwarded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AssistantApiTests {

    private static final String DOCUMENT = """
            {"title":"Leave policy","content":"25 days of annual leave.",
             "allowedRoles":["EMPLOYEE","HR_ADMIN"]}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AiClient aiClient;

    @Test
    void employeesCanAskQuestions() throws Exception {
        given(aiClient.ask("How much leave?")).willReturn(new AskResponse(
                "25 days [1]", List.of(new Source(UUID.randomUUID(), "Leave policy", 0.42)), "claude"));

        mvc.perform(post("/api/assistant/ask").with(user(newTenant(), "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How much leave?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("25 days [1]"))
                .andExpect(jsonPath("$.sources[0].title").value("Leave policy"));
    }

    @Test
    void employeesCannotAddDocuments() throws Exception {
        mvc.perform(post("/api/assistant/documents").with(user(newTenant(), "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DOCUMENT))
                .andExpect(status().isForbidden());

        verifyNoInteractions(aiClient); // rejected before reaching the AI service
    }

    @Test
    void hrAdminsCanAddDocuments() throws Exception {
        given(aiClient.uploadDocument(any())).willReturn(new UploadDocumentResponse(UUID.randomUUID(), 1));

        mvc.perform(post("/api/assistant/documents").with(user(newTenant(), "HR_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DOCUMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunks").value(1));

        ArgumentCaptor<UploadDocumentRequest> sent = ArgumentCaptor.forClass(UploadDocumentRequest.class);
        verify(aiClient).uploadDocument(sent.capture());
        assertThat(sent.getValue().allowedRoles()).containsExactlyInAnyOrder(Role.EMPLOYEE, Role.HR_ADMIN);
    }

    @Test
    void unknownRolesAreRejected() throws Exception {
        mvc.perform(post("/api/assistant/documents").with(user(newTenant(), "HR_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","content":"y","allowedRoles":["BOSS"]}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiClient);
    }
}
