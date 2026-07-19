package dev.escalade.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.escalade.common.ApiError;
import dev.escalade.organization.Organization;
import dev.escalade.organization.OrganizationRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller's {@link Organization} from an {@code Authorization: Bearer <api_key>}
 * header and stashes it on the request. Only {@code /api/**} is guarded; docs, actuator and
 * the root path pass through unauthenticated.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String ORG_ATTRIBUTE = "dev.escalade.currentOrg";
    private static final String BEARER = "Bearer ";

    private final OrganizationRepository organizations;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(OrganizationRepository organizations, ObjectMapper objectMapper) {
        this.organizations = organizations;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            reject(response, "Missing or malformed Authorization header (expected 'Bearer <api_key>')");
            return;
        }

        String apiKey = header.substring(BEARER.length()).trim();
        Organization org = organizations.findByApiKey(apiKey).orElse(null);
        if (org == null) {
            reject(response, "Invalid API key");
            return;
        }

        request.setAttribute(ORG_ATTRIBUTE, org);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized", message);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
