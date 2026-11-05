package com.llmdiscovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LlmDiscoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmDiscoveryApplication.class, args);
    }
}
