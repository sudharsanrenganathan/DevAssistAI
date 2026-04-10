package com.devassist.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);  // 10 seconds connection timeout
        factory.setReadTimeout(60000);     // 60 seconds read timeout for large documents
        factory.setBufferRequestBody(false); // Don't buffer for streaming
        return new RestTemplate(factory);
    }
}