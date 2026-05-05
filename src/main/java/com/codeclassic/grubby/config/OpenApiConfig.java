package com.codeclassic.grubby.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI grubbyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Grubby BRD Generator API")
                        .description("AI-powered Business Requirements Document generation from Git repositories")
                        .version("v1")
                        .contact(new Contact().name("Code-Classic").url("https://github.com/Code-Classic/grubby"))
                        .license(new License().name("MIT")));
    }
}
