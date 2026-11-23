package com.hrms.core.tenant;

import com.hrms.core.security.CurrentUser;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Tells Hibernate which tenant the current request belongs to.
 *
 * <p>Together with {@code @TenantId} on an entity field, Hibernate then:
 * <ul>
 *   <li>adds {@code WHERE tenant_id = ?} to every query and lookup for that entity, and</li>
 *   <li>fills in {@code tenant_id} automatically on insert.</li>
 * </ul>
 * So a developer cannot "forget" the tenant filter in a new query. That is the most
 * common way multi-tenant apps leak data.
 *
 * <p>Registered in application.yml via {@code hibernate.tenant_identifier_resolver}.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    /**
     * Used when there is no authenticated tenant (e.g. app startup). It matches no rows, so
     * the failure mode is "sees nothing", never "sees everything".
     */
    static final String NO_TENANT = "__no_tenant__";

    @Override
    public String resolveCurrentTenantIdentifier() {
        return CurrentUser.tenantId().orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
