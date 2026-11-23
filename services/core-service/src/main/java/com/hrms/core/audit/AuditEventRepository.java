package com.hrms.core.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /** Derived query: Spring Data builds the SQL from the method name. */
    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, String entityId);
}
