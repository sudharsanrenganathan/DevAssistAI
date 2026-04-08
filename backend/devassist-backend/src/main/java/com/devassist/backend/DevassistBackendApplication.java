package com.devassist.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.devassist.backend")
public class DevassistBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevassistBackendApplication.class, args);
    }
}