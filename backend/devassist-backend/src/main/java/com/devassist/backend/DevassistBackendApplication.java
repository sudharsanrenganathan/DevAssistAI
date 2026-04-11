package com.devassist.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.devassist.backend")
@EnableScheduling
public class DevassistBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevassistBackendApplication.class, args);
    }
}