package com.hrms.core.leave;

import static com.hrms.core.TestUsers.newTenant;
import static com.hrms.core.TestUsers.person;
import static com.hrms.core.TestUsers.unverified;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hrms.core.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The leave workflow end to end, through the HTTP API, against a real database.
 * Each test builds its own small company: an HR admin, a manager and one of their reports.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeaveApiTests {

    private static final LocalDate MONDAY = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

    @Autowired
    MockMvc mvc;

    /** A small company for one test. */
    record Team(String tenant, String managerId, String employeeId) {
        RequestPostProcessor hr() { return person(tenant, "hr@" + tenant + ".test", "HR_ADMIN", "EMPLOYEE"); }
        RequestPostProcessor manager() { return person(tenant, "manager@" + tenant + ".test", "MANAGER", "EMPLOYEE"); }
        RequestPostProcessor employee() { return person(tenant, "employee@" + tenant + ".test", "EMPLOYEE"); }
    }

    @Test
    void requestGoesThroughManagerThenHr() throws Exception {
        Team team = newTeam();
        String id = submit(team.employee(), "ANNUAL", MONDAY, MONDAY.plusDays(2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_MANAGER"))
                .andExpect(jsonPath("$.workingDays").value(3))
                .andExpect(jsonPath("$.allowedActions", contains("CANCEL")))
                .andReturn().getResponse().getContentAsString();
        id = JsonPath.read(id, "$.id");

        // Nobody can approve their own request, and HR can't skip the manager step
        decide(team.employee(), id, "approve", null).andExpect(status().isForbidden());
        decide(team.hr(), id, "approve", null).andExpect(status().isForbidden());

        // The manager sees it in their queue, with the buttons they may use
        mvc.perform(get("/api/leave/requests/approvals").with(team.manager()))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].allowedActions", contains("APPROVE", "REJECT")));
        decide(team.manager(), id, "approve", "Enjoy!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_HR"));

        // Step two belongs to HR, not the manager
        decide(team.manager(), id, "approve", null).andExpect(status().isForbidden());
        decide(team.hr(), id, "approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.allowedActions").isEmpty());

        // Final means final
        decide(team.employee(), id, "cancel", null).andExpect(status().isConflict());

        mvc.perform(get("/api/leave/requests/" + id).with(team.employee()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[1].action").value("APPROVE"))
                .andExpect(jsonPath("$.history[1].comment").value("Enjoy!"))
                .andExpect(jsonPath("$.history[2].toStatus").value("APPROVED"));
    }

    @Test
    void annualLeaveUsesUpTheAllowance() throws Exception {
        assumeTrue(MONDAY.getYear() == LocalDate.now().getYear(), "balance is per calendar year");
        Team team = newTeam();
        String id = idOf(submit(team.employee(), "ANNUAL", MONDAY, MONDAY.plusDays(2)));

        mvc.perform(get("/api/leave/me").with(team.employee()))
                .andExpect(jsonPath("$.manager.name").value("Mia Manager"))
                .andExpect(jsonPath("$.balance.allowanceDays").value(25))
                .andExpect(jsonPath("$.balance.pendingDays").value(3))
                .andExpect(jsonPath("$.balance.remainingDays").value(22));

        decide(team.manager(), id, "approve", null).andExpect(status().isOk());
        decide(team.hr(), id, "approve", null).andExpect(status().isOk());

        mvc.perform(get("/api/leave/me").with(team.employee()))
                .andExpect(jsonPath("$.balance.usedDays").value(3))
                .andExpect(jsonPath("$.balance.pendingDays").value(0))
                .andExpect(jsonPath("$.balance.remainingDays").value(22));
    }

    @Test
    void rejectingNeedsAReason() throws Exception {
        Team team = newTeam();
        String id = idOf(submit(team.employee(), "SICK", MONDAY, MONDAY));

        decide(team.manager(), id, "reject", "").andExpect(status().isBadRequest());
        decide(team.manager(), id, "reject", "Team offsite that day")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void employeesCanCancelWhilePending() throws Exception {
        Team team = newTeam();
        String id = idOf(submit(team.employee(), "UNPAID", MONDAY, MONDAY));

        decide(team.employee(), id, "cancel", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        decide(team.manager(), id, "approve", null).andExpect(status().isConflict());
    }

    @Test
    void employeesWithoutAManagerGoStraightToHr() throws Exception {
        Team team = newTeam();
        createEmployee(team, "SOLO-1", "Sol", "solo@" + team.tenant() + ".test", null);

        submit(person(team.tenant(), "solo@" + team.tenant() + ".test", "EMPLOYEE"), "ANNUAL", MONDAY, MONDAY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_HR"))
                .andExpect(jsonPath("$.approver").isEmpty());
    }

    @Test
    void requestsAreHiddenFromEveryoneElse() throws Exception {
        Team team = newTeam();
        createEmployee(team, "COL-1", "Cole", "colleague@" + team.tenant() + ".test", team.managerId());
        String id = idOf(submit(team.employee(), "ANNUAL", MONDAY, MONDAY));

        // A colleague with the same manager, and HR of another company, get 404: not even "it exists"
        RequestPostProcessor colleague = person(team.tenant(), "colleague@" + team.tenant() + ".test", "EMPLOYEE");
        RequestPostProcessor otherCompanyHr = person(newTenant(), "hr@elsewhere.test", "HR_ADMIN");
        for (RequestPostProcessor outsider : List.of(colleague, otherCompanyHr)) {
            mvc.perform(get("/api/leave/requests/" + id).with(outsider)).andExpect(status().isNotFound());
            decide(outsider, id, "approve", null).andExpect(status().isNotFound());
        }
    }

    @Test
    void businessRulesAreEnforced() throws Exception {
        Team team = newTeam();
        submit(team.employee(), "ANNUAL", MONDAY.plusDays(2), MONDAY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("endDate"));
        submit(team.employee(), "ANNUAL", MONDAY.minusDays(2), MONDAY.minusDays(1)) // a weekend
                .andExpect(status().isUnprocessableContent());
        submit(team.employee(), "ANNUAL", MONDAY.minusWeeks(2), MONDAY.minusWeeks(2))
                .andExpect(jsonPath("$.errors[0].field").value("startDate"));     // in the past
        submit(team.employee(), "ANNUAL", MONDAY, MONDAY.plusWeeks(6))            // 31 days > 25
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value(startsWith("you have 25 days")));

        submit(team.employee(), "ANNUAL", MONDAY, MONDAY.plusDays(2)).andExpect(status().isCreated());
        submit(team.employee(), "SICK", MONDAY.plusDays(1), MONDAY.plusDays(1))  // overlaps
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("overlaps another leave request of yours"));
    }

    @Test
    void accountsNeedAVerifiedEmailThatMatchesAnEmployee() throws Exception {
        Team team = newTeam();

        mvc.perform(get("/api/leave/me").with(person(team.tenant(), "nobody@" + team.tenant() + ".test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee").isEmpty());
        // An unverified email that happens to match an employee must not link to them
        mvc.perform(get("/api/leave/me").with(unverified(team.tenant(), "employee@" + team.tenant() + ".test")))
                .andExpect(jsonPath("$.employee").isEmpty());
        submit(unverified(team.tenant(), "employee@" + team.tenant() + ".test"), "ANNUAL", MONDAY, MONDAY)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void pendingApprovalsFollowTheNewManager() throws Exception {
        Team team = newTeam();
        String id = idOf(submit(team.employee(), "ANNUAL", MONDAY, MONDAY));
        String newManagerId = createEmployee(team, "MGR-2", "Max", "max@" + team.tenant() + ".test", null);
        RequestPostProcessor newManager = person(team.tenant(), "max@" + team.tenant() + ".test", "MANAGER");

        changeManager(team, team.employeeId(), newManagerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerName").value("Max Example"));

        // The request moved from Mia's queue to Max's
        mvc.perform(get("/api/leave/requests/approvals").with(team.manager()))
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/leave/requests/approvals").with(newManager))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].approver.name").value("Max Example"));
        decide(team.manager(), id, "approve", null).andExpect(status().isNotFound()); // no longer hers to see

        mvc.perform(get("/api/leave/requests/" + id).with(team.employee()))
                .andExpect(jsonPath("$.history[1].action").value("REASSIGN"))
                .andExpect(jsonPath("$.history[1].comment").value("Approver changed from Mia Manager to Max Example"));
        decide(newManager, id, "approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_HR"));
    }

    @Test
    void removingTheManagerSendsPendingRequestsToHr() throws Exception {
        Team team = newTeam();
        String id = idOf(submit(team.employee(), "SICK", MONDAY, MONDAY));

        changeManager(team, team.employeeId(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerId").isEmpty());

        mvc.perform(get("/api/leave/requests/" + id).with(team.employee()))
                .andExpect(jsonPath("$.request.status").value("PENDING_HR"))
                .andExpect(jsonPath("$.request.approver").isEmpty());
        mvc.perform(get("/api/leave/requests/approvals").with(team.hr()))
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void decidedRequestsKeepTheirApproverAfterAReorg() throws Exception {
        Team team = newTeam();
        String id = idOf(submit(team.employee(), "ANNUAL", MONDAY, MONDAY));
        decide(team.manager(), id, "approve", null).andExpect(status().isOk()); // now waiting for HR

        changeManager(team, team.employeeId(), null).andExpect(status().isOk());

        mvc.perform(get("/api/leave/requests/" + id).with(team.employee()))
                .andExpect(jsonPath("$.request.status").value("PENDING_HR"))
                .andExpect(jsonPath("$.request.approver.name").value("Mia Manager"))
                .andExpect(jsonPath("$.history.length()").value(2)); // no REASSIGN step
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Team newTeam() throws Exception {
        String tenant = newTenant();
        Team team = new Team(tenant, null, null);
        String managerId = createEmployee(team, "MGR-1", "Mia", "manager@" + tenant + ".test", null);
        String employeeId = createEmployee(team, "EMP-1", "Eli", "employee@" + tenant + ".test", managerId);
        return new Team(tenant, managerId, employeeId);
    }

    private ResultActions changeManager(Team team, String employeeId, String managerId) throws Exception {
        return mvc.perform(put("/api/employees/" + employeeId + "/manager").with(team.hr())
                .contentType(MediaType.APPLICATION_JSON)
                .content(managerId == null ? "{\"managerId\":null}" : "{\"managerId\":\"%s\"}".formatted(managerId)));
    }

    private String createEmployee(Team team, String code, String firstName, String email, String managerId)
            throws Exception {
        String body = """
                {"employeeCode":"%s","firstName":"%s","lastName":"%s","email":"%s",
                 "hireDate":"2024-01-15","managerId":%s}"""
                .formatted(code, firstName, firstName.equals("Mia") ? "Manager" : "Example", email,
                        managerId == null ? "null" : "\"" + managerId + "\"");
        String response = mvc.perform(post("/api/employees").with(team.hr())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private ResultActions submit(RequestPostProcessor who, String type, LocalDate start, LocalDate end)
            throws Exception {
        return mvc.perform(post("/api/leave/requests").with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"%s","startDate":"%s","endDate":"%s","reason":"Family visit"}"""
                        .formatted(type, start, end)));
    }

    private ResultActions decide(RequestPostProcessor who, String id, String action, String comment)
            throws Exception {
        var request = post("/api/leave/requests/" + id + "/" + action).with(who);
        if (comment != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":\"%s\"}".formatted(comment));
        }
        return mvc.perform(request);
    }

    private String idOf(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
