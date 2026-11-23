package com.hrms.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Plain unit test: no Spring context, runs in milliseconds. */
class KeycloakRealmRoleConverterTests {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsRealmRolesToSpringAuthorities() {
        Jwt jwt = jwtWithClaim("realm_access", Map.of("roles", List.of("HR_ADMIN", "EMPLOYEE")));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_HR_ADMIN", "ROLE_EMPLOYEE");
    }

    @Test
    void missingRealmAccessMeansNoRoles() {
        Jwt jwt = jwtWithClaim("scope", "openid");

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void malformedRolesClaimMeansNoRoles() {
        Jwt jwt = jwtWithClaim("realm_access", Map.of("roles", "HR_ADMIN")); // a string, not a list

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("user-1")
                .claim(name, value)
                .build();
    }
}
