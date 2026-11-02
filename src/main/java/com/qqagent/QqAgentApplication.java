package com.qqagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QqAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(QqAgentApplication.class, args);
    }
}
