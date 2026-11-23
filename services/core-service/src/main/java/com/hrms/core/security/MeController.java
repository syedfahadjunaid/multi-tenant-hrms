package com.hrms.core.security;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows what the API believes about the caller. The web app uses it for the user's name and
 * roles (it never sees a token itself: the BFF keeps those server-side). Also handy for
 * debugging tokens.
 */
@RestController
public class MeController {

    public record MeResponse(
            String subject,
            String username,
            String displayName,
            String firstName,
            String tenantId,
            List<String> roles) {}

    @GetMapping("/api/me")
    public MeResponse me() {
        return new MeResponse(
                CurrentUser.subject(),
                CurrentUser.username(),
                CurrentUser.displayName(),
                CurrentUser.firstName(),
                CurrentUser.tenantId().orElse(null),
                CurrentUser.roles());
    }
}
