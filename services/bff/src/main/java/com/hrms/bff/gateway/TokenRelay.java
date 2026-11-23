package com.hrms.bff.gateway;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Adds the signed-in user's access token to a request before it is forwarded to the API.
 *
 * <p>Spring Cloud Gateway ships a similar filter ({@code TokenRelayFilterFunctions}), but it
 * forwards the request without a token when it can't get one. This one fails closed: if
 * there is no token, or Keycloak rejects the refresh token (its session ended), the request
 * stops here with 401 and the BFF session is ended, so the SPA signs the user in again.
 */
@Component
public class TokenRelay implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private static final Logger log = LoggerFactory.getLogger(TokenRelay.class);

    private final OAuth2AuthorizedClientManager authorizedClients;

    public TokenRelay(OAuth2AuthorizedClientManager authorizedClients) {
        this.authorizedClients = authorizedClients;
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        if (!(request.servletRequest().getUserPrincipal() instanceof OAuth2AuthenticationToken user)) {
            return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
        }

        OAuth2AuthorizedClient client;
        try {
            // Returns the stored token, or refreshes it first if it has (nearly) expired.
            client = authorizedClients.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(user.getAuthorizedClientRegistrationId())
                    .principal(user)
                    .build());
        } catch (OAuth2AuthorizationException e) {
            log.info("Token refresh rejected for {} ({}); ending the session",
                    user.getName(), e.getError().getErrorCode());
            client = null;
        }
        if (client == null) {
            HttpSession session = request.servletRequest().getSession(false);
            if (session != null) {
                session.invalidate();
            }
            return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
        }

        String accessToken = client.getAccessToken().getTokenValue();
        return next.handle(ServerRequest.from(request)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .build());
    }
}
