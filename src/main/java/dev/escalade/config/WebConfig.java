package dev.escalade.config;

import dev.escalade.auth.CurrentOrgArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentOrgArgumentResolver currentOrgArgumentResolver;

    public WebConfig(CurrentOrgArgumentResolver currentOrgArgumentResolver) {
        this.currentOrgArgumentResolver = currentOrgArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentOrgArgumentResolver);
    }
}
