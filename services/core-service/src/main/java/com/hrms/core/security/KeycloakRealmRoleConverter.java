package com.hrms.core.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Keycloak puts realm roles in the token like this:
 *
 * <pre>{@code
 * "realm_access": { "roles": ["HR_ADMIN", "EMPLOYEE"] }
 * }</pre>
 *
 * Spring Security expects authorities named {@code ROLE_<name>} for {@code hasRole("<name>")}
 * checks, so this converter maps one to the other. Anything malformed simply yields no roles
 * (fail closed) instead of throwing.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
