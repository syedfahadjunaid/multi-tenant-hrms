package com.hrms.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the HRMS core API.
 *
 * <p>{@code @SpringBootApplication} turns on component scanning for this package and its
 * sub-packages, so every {@code @RestController}, {@code @Service}, {@code @Configuration}
 * etc. below {@code com.hrms.core} is picked up automatically.
 *
 * <p>{@code @ConfigurationPropertiesScan} finds {@link com.hrms.core.config.HrmsProperties}
 * and binds the {@code hrms.*} section of application.yml to it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreServiceApplication.class, args);
    }
}
