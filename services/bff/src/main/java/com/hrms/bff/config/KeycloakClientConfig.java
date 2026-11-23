package com.hrms.bff.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * How the BFF talks to Keycloak.
 *
 * <p>The registration is built in code rather than from {@code spring.security.oauth2.client.*}
 * properties, because the browser and this service reach Keycloak at different addresses:
 * the browser is sent to {@code http://localhost:8180}, while inside Docker this service
 * calls {@code http://keycloak:8080}. Spring's property-based setup would fetch Keycloak's
 * discovery document from the issuer URL at startup, which isn't reachable from a container.
 */
@Configuration
public class KeycloakClientConfig {

    /** Also the last part of the login URL: {@code /oauth2/authorization/keycloak}. */
    public static final String REGISTRATION_ID = "keycloak";

    @Bean
    ClientRegistrationRepository clientRegistrations(BffProperties props) {
        BffProperties.Keycloak keycloak = props.keycloak();
        String browserFacing = keycloak.issuerUri() + "/protocol/openid-connect";
        String backchannel = keycloak.backchannelUri() + "/protocol/openid-connect";

        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("Keycloak")
                // A confidential client: it proves who it is with a secret the browser never sees.
                .clientId(keycloak.clientId())
                .clientSecret(keycloak.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // PKCE on top of the secret, as the OAuth 2.0 security best practice recommends.
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .redirectUri(props.publicUrl() + "/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                // ID tokens are checked against this issuer (signature, iss, aud, expiry, nonce).
                .issuerUri(keycloak.issuerUri())
                .authorizationUri(browserFacing + "/auth") // the browser is redirected here
                .tokenUri(backchannel + "/token") // server-to-server
                .jwkSetUri(backchannel + "/certs") // server-to-server
                .userNameAttributeName("preferred_username")
                // Normally read from the discovery document; used to sign out of Keycloak too.
                .providerConfigurationMetadata(Map.of("end_session_endpoint", browserFacing + "/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * Where each user's access and refresh tokens are kept: in their HTTP session, which
     * Spring Session stores in Redis. (Spring Boot's default keeps them in a map in this JVM,
     * so they'd be lost on restart and invisible to a second BFF instance.)
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** Hands out a valid access token, using the refresh token when the old one has expired. */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients) {
        var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build());
        return manager;
    }
}
