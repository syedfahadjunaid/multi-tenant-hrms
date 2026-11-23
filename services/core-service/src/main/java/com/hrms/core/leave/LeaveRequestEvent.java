package com.hrms.core.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/** One step in a request's history: who moved it from which status to which, and why. */
@Entity
@Immutable
@Table(name = "leave_request_events")
public class LeaveRequestEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_request_id", nullable = false)
    private LeaveRequest leaveRequest;

    /** SUBMIT, or one of {@link LeaveAction}. */
    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private LeaveStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private LeaveStatus toStatus;

    @Column(name = "actor_subject", nullable = false)
    private String actorSubject;

    @Column(name = "actor_name", nullable = false)
    private String actorName;

    private String comment;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LeaveRequestEvent() {}

    LeaveRequestEvent(LeaveRequest leaveRequest, String action, LeaveStatus fromStatus, LeaveStatus toStatus,
                      String actorSubject, String actorName, String comment) {
        this.leaveRequest = leaveRequest;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorSubject = actorSubject;
        this.actorName = actorName;
        this.comment = comment;
        this.occurredAt = Instant.now();
    }

    public String getAction() { return action; }
    public LeaveStatus getFromStatus() { return fromStatus; }
    public LeaveStatus getToStatus() { return toStatus; }
    public String getActorName() { return actorName; }
    public String getComment() { return comment; }
    public Instant getOccurredAt() { return occurredAt; }
}
