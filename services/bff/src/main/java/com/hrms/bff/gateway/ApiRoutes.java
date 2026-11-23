package com.hrms.bff.gateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import com.hrms.bff.config.BffProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Spring Cloud Gateway routes. One route today: the core API.
 *
 * <p>Filters run in the order they are declared. Security (sign-in, CSRF) has already been
 * checked by the time a request gets here (see SecurityConfig).
 */
@Configuration
public class ApiRoutes {

    @Bean
    RouterFunction<ServerResponse> coreApi(BffProperties props, TokenRelay tokenRelay) {
        return route("core-api")
                .route(path("/api/**"), http())
                .before(uri(props.coreServiceUrl()))
                // The browser's cookies (session id, CSRF token) are for the BFF only. The API
                // gets a bearer token, exactly as from any other OAuth2 client.
                .before(removeRequestHeader(HttpHeaders.COOKIE))
                .before(removeRequestHeader("X-XSRF-TOKEN"))
                .filter(tokenRelay)
                .build();
    }
}
