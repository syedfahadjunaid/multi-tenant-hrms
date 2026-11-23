package com.hrms.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The backend-for-frontend (BFF): the only server the browser talks to for data.
 *
 * <p>Why it exists: a single-page app that holds OAuth2 tokens in JavaScript can lose them to
 * any script that runs on the page (an XSS bug, a compromised npm package). Here the browser
 * gets only an HttpOnly session cookie, which JavaScript cannot read. This service signs the
 * user in with Keycloak, keeps the access and refresh tokens in the server-side session, and
 * adds the access token to each API call it forwards to core-service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
