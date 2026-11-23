package com.hrms.core.leave;

import com.hrms.core.leave.LeaveDtos.ApproveRequest;
import com.hrms.core.leave.LeaveDtos.LeaveOverview;
import com.hrms.core.leave.LeaveDtos.LeaveRequestDetail;
import com.hrms.core.leave.LeaveDtos.LeaveRequestResponse;
import com.hrms.core.leave.LeaveDtos.NewLeaveRequest;
import com.hrms.core.leave.LeaveDtos.RejectRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leave API. Every signed-in user may call these endpoints; what they may do to a particular
 * request depends on their relationship to it, which LeaveService checks.
 */
@RestController
@RequestMapping("/api/leave")
public class LeaveController {

    private final LeaveService service;

    public LeaveController(LeaveService service) {
        this.service = service;
    }

    /** The signed-in user's employee record, manager and annual leave balance. */
    @GetMapping("/me")
    public LeaveOverview overview() {
        return service.overview();
    }

    @GetMapping("/requests/mine")
    public List<LeaveRequestResponse> mine() {
        return service.myRequests();
    }

    /** Requests waiting for the signed-in user to approve or reject. */
    @GetMapping("/requests/approvals")
    public List<LeaveRequestResponse> approvals() {
        return service.awaitingMyDecision();
    }

    @GetMapping("/requests/{id}")
    public LeaveRequestDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/requests")
    public ResponseEntity<LeaveRequestResponse> submit(@Valid @RequestBody NewLeaveRequest request) {
        LeaveRequestResponse created = service.submit(request);
        return ResponseEntity.created(URI.create("/api/leave/requests/" + created.id())).body(created);
    }

    @PostMapping("/requests/{id}/approve")
    public LeaveRequestResponse approve(@PathVariable UUID id,
                                        @Valid @RequestBody(required = false) ApproveRequest body) {
        return service.decide(id, LeaveAction.APPROVE, body == null ? null : body.comment());
    }

    @PostMapping("/requests/{id}/reject")
    public LeaveRequestResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest body) {
        return service.decide(id, LeaveAction.REJECT, body.comment());
    }

    @PostMapping("/requests/{id}/cancel")
    public LeaveRequestResponse cancel(@PathVariable UUID id) {
        return service.decide(id, LeaveAction.CANCEL, null);
    }
}
