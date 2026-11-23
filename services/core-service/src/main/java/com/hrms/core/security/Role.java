package com.hrms.core.security;

/**
 * Realm roles defined in Keycloak (infra/keycloak/hrms-realm.json).
 *
 * <p>An enum gives compile-time safety: JSON like {@code "allowedRoles": ["BOSS"]} is rejected
 * with a 400 before any of our code runs.
 */
public enum Role {
    HR_ADMIN,
    MANAGER,
    EMPLOYEE
}
