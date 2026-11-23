package com.hrms.core.audit;

import com.hrms.core.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final int MAX_DETAILS = 500;

    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    /**
     * {@code MANDATORY}: must be called inside the caller's transaction, so the audit record
     * and the change it describes are committed together, or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId) {
        record(action, entityType, entityId, null);
    }

    /** Same, with a short description of what changed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, String details) {
        String trimmed = details == null || details.length() <= MAX_DETAILS ? details : details.substring(0, MAX_DETAILS);
        events.save(new AuditEvent(CurrentUser.subject(), action, entityType, String.valueOf(entityId), trimmed));
    }
}
