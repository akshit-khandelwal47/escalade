package dev.escalade.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI escaladeOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Escalade API")
                .version("0.1.0")
                .description("Self-hostable on-call / escalation engine. "
                        + "Authenticate with 'Authorization: Bearer <org api_key>'.")
                .license(new License().name("MIT")));
    }
}
