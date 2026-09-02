package com.auditflow.alerting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AlertingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertingServiceApplication.class, args);
    }
}
