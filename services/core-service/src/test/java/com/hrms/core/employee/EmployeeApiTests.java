package com.hrms.core.employee;

import static com.hrms.core.TestUsers.newTenant;
import static com.hrms.core.TestUsers.runAsTenant;
import static com.hrms.core.TestUsers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.core.TestcontainersConfiguration;
import com.hrms.core.audit.AuditEvent;
import com.hrms.core.audit.AuditEventRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests: the full Spring app against real Postgres + Redis (Testcontainers).
 *
 * <p>{@code jwt()} from spring-security-test injects an already-verified token, so we can
 * test authorization rules without running Keycloak. Each test uses fresh tenant ids so
 * tests don't see each other's data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmployeeApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuditEventRepository auditEvents;

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/employees"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsWhatTheTokenSays() throws Exception {
        String tenant = newTenant();
        mvc.perform(get("/api/me").with(user(tenant, "MANAGER", "EMPLOYEE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant))
                .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"))
                .andExpect(jsonPath("$.roles[1]").value("MANAGER"))
                // No name claims in this token: both fall back to the username.
                .andExpect(jsonPath("$.displayName").value("user-" + tenant))
                .andExpect(jsonPath("$.firstName").value("user-" + tenant));
    }

    @Test
    void meReturnsTheNameFromTheToken() throws Exception {
        mvc.perform(get("/api/me").with(jwt().jwt(token -> token
                        .subject("mark-id")
                        .claim("preferred_username", "mark")
                        .claim("name", "Mark Manager")
                        .claim("given_name", "Mark")
                        .claim("tenant_id", newTenant()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Mark Manager"))
                .andExpect(jsonPath("$.firstName").value("Mark"));
    }

    @Test
    void employeesCannotCreateEmployees() throws Exception {
        mvc.perform(post("/api/employees").with(user(newTenant(), "EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeJson("E-1", "eve@acme.test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidInputIsRejectedWithProblemDetails() throws Exception {
        mvc.perform(post("/api/employees").with(user(newTenant(), "HR_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employeeCode":"bad code!","firstName":"","lastName":"X",
                                 "email":"not-an-email","hireDate":"2999-01-01"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                // Field-level errors, so the UI can show each message next to its input
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'employeeCode')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'hireDate')]").exists());
    }

    @Test
    void tenantsAreIsolatedFromEachOther() throws Exception {
        String acme = newTenant();
        String globex = newTenant();

        String body = mvc.perform(post("/api/employees").with(user(acme, "HR_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeJson("ACME-001", "Dana@Acme.test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("dana@acme.test"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        // Same tenant: visible
        mvc.perform(get("/api/employees").with(user(acme, "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].employeeCode").value("ACME-001"));

        // Other tenant: invisible, both in lists and by direct id
        mvc.perform(get("/api/employees").with(user(globex, "HR_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/employees/" + id).with(user(globex, "HR_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateEmailInSameTenantIsAConflict() throws Exception {
        String tenant = newTenant();
        mvc.perform(post("/api/employees").with(user(tenant, "HR_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeJson("D-1", "dup@acme.test")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/employees").with(user(tenant, "HR_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeJson("D-2", "dup@acme.test")))
                .andExpect(status().isConflict());
    }

    @Test
    void creatingAnEmployeeWritesAnAuditEvent() throws Exception {
        String tenant = newTenant();
        String body = mvc.perform(post("/api/employees").with(user(tenant, "HR_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeJson("A-1", "audit@acme.test")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        // The audit table is tenant-filtered too, so read it back as that tenant.
        List<AuditEvent> events = runAsTenant(tenant,
                () -> auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("Employee", id));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getAction()).isEqualTo("EMPLOYEE_CREATED");
            assertThat(event.getActor()).isEqualTo("user-" + tenant);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static String employeeJson(String code, String email) {
        return """
                {"employeeCode":"%s","firstName":"Dana","lastName":"Lee","email":"%s",
                 "department":"Engineering","jobTitle":"Engineer","hireDate":"2024-03-01"}"""
                .formatted(code, email);
    }
}
