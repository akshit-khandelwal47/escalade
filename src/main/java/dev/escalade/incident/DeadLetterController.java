package dev.escalade.incident;

import dev.escalade.auth.CurrentOrg;
import dev.escalade.incident.IncidentDtos.DeadLetterResponse;
import dev.escalade.organization.Organization;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliveries that exhausted their retries. Exposing these is the point of the dead-letter table:
 * a page that could not be delivered has to be visible to an operator, not buried in a log.
 */
@RestController
@RequestMapping("/api/v1/dead-letters")
public class DeadLetterController {

    private final IncidentService incidentService;

    public DeadLetterController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    public List<DeadLetterResponse> list(@CurrentOrg Organization org) {
        return incidentService.listDeadLetters(org.getId());
    }
}
