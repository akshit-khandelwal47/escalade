package dev.escalade.config;

import dev.escalade.organization.Organization;
import dev.escalade.organization.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seeds a single demo organization with a fixed API key on startup, so the API is
 * curl-able immediately. Enabled with {@code escalade.seed-demo=true}; off by default.
 */
@Configuration
@ConditionalOnProperty(name = "escalade.seed-demo", havingValue = "true")
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String DEMO_API_KEY = "esc_demo_key_local";

    @Bean
    public ApplicationRunner seedDemoOrg(OrganizationRepository organizations) {
        return args -> {
            if (organizations.findByApiKey(DEMO_API_KEY).isPresent()) {
                log.info("Demo org already present (api_key={})", DEMO_API_KEY);
                return;
            }
            Organization org = organizations.save(new Organization("Demo Org", DEMO_API_KEY));
            log.info("Seeded demo org id={} api_key={}", org.getId(), DEMO_API_KEY);
        };
    }
}
