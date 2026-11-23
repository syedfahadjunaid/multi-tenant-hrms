package com.hrms.core.employee;

import static com.hrms.core.TestUsers.newTenant;
import static com.hrms.core.TestUsers.runAsTenant;
import static com.hrms.core.TestUsers.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.core.TestcontainersConfiguration;
import com.hrms.core.audit.AuditEvent;
import com.hrms.core.audit.AuditEventRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Changing who reports to whom: permissions, loops, and the audit trail. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportingLineApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuditEventRepository auditEvents;

    private String tenant;

    @BeforeEach
    void newCompany() {
        tenant = newTenant();
    }

    @Test
    void hrCanMoveAnEmployeeToAnotherManagerOrToNone() throws Exception {
        String ann = create("Ann", null);
        String ben = create("Ben", ann);
        String cat = create("Cat", null);

        changeManager(hr(), ben, cat)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerId").value(cat))
                .andExpect(jsonPath("$.managerName").value("Cat Example"));
        changeManager(hr(), ben, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerName").isEmpty());

        // Every change is in the audit trail, with what changed
        var details = runAsTenant(tenant, () -> auditEvents
                .findByEntityTypeAndEntityIdOrderByOccurredAtAsc("Employee", ben).stream()
                .map(AuditEvent::getDetails)
                .toList());
        assertThat(details).containsSubsequence("manager: Ann Example -> Cat Example", "manager: Cat Example -> none");
    }

    @Test
    void onlyHrCanChangeReportingLines() throws Exception {
        String ann = create("Ann", null);
        String ben = create("Ben", null);

        changeManager(user(tenant, "MANAGER"), ben, ann).andExpect(status().isForbidden());
    }

    @Test
    void nobodyCanManageThemselves() throws Exception {
        String ann = create("Ann", null);

        changeManager(hr(), ann, ann)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("managerId"))
                .andExpect(jsonPath("$.errors[0].message").value("an employee can't be their own manager"));
    }

    @Test
    void loopsInTheOrgChartAreRejected() throws Exception {
        String ann = create("Ann", null);  // Ann
        String ben = create("Ben", ann);   //  └─ Ben
        String cat = create("Cat", ben);   //      └─ Cat

        // Ann reporting to Cat would make Ann her own (indirect) manager
        changeManager(hr(), ann, cat)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Cat Example already reports to Ann Example, directly or indirectly"));
        // Moving sideways is fine
        changeManager(hr(), cat, ann).andExpect(status().isOk());
    }

    @Test
    void managersMustExistInTheSameCompany() throws Exception {
        String ann = create("Ann", null);
        String otherCompany = tenant;
        tenant = newTenant();
        String outsider = create("Olga", null);
        tenant = otherCompany;

        changeManager(hr(), ann, outsider)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("no such employee"));
        changeManager(hr(), UUID.randomUUID().toString(), ann).andExpect(status().isNotFound());
    }

    // ---- helpers ---------------------------------------------------------------------------

    private RequestPostProcessor hr() {
        return user(tenant, "HR_ADMIN");
    }

    private String create(String firstName, String managerId) throws Exception {
        String body = """
                {"employeeCode":"%s","firstName":"%s","lastName":"Example","email":"%s@%s.test",
                 "hireDate":"2024-01-15","managerId":%s}"""
                .formatted(firstName.toUpperCase(), firstName, firstName.toLowerCase(), tenant,
                        managerId == null ? "null" : "\"" + managerId + "\"");
        String response = mvc.perform(post("/api/employees").with(hr())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private ResultActions changeManager(RequestPostProcessor who, String employeeId, String managerId)
            throws Exception {
        return mvc.perform(put("/api/employees/" + employeeId + "/manager").with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(managerId == null ? "{\"managerId\":null}" : "{\"managerId\":\"%s\"}".formatted(managerId)));
    }
}
