package dev.escalade.incident;

import dev.escalade.auth.CurrentOrg;
import dev.escalade.incident.IncidentDtos.CreateIncidentRequest;
import dev.escalade.incident.IncidentDtos.IncidentResponse;
import dev.escalade.incident.IncidentService.CreateResult;
import dev.escalade.organization.Organization;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    /** 201 for a freshly created incident, 200 when an existing OPEN incident was deduplicated. */
    @PostMapping
    public ResponseEntity<IncidentResponse> create(
            @CurrentOrg Organization org, @Valid @RequestBody CreateIncidentRequest req) {
        CreateResult result = incidentService.create(org.getId(), req);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.incident());
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@CurrentOrg Organization org, @PathVariable UUID id) {
        return incidentService.get(org.getId(), id);
    }

    @GetMapping
    public List<IncidentResponse> list(@CurrentOrg Organization org, @RequestParam(required = false) IncidentStatus status) {
        return incidentService.list(org.getId(), status);
    }

    @PostMapping("/{id}/ack")
    public IncidentResponse acknowledge(@CurrentOrg Organization org, @PathVariable UUID id) {
        return incidentService.acknowledge(org.getId(), id);
    }

    @PostMapping("/{id}/resolve")
    public IncidentResponse resolve(@CurrentOrg Organization org, @PathVariable UUID id) {
        return incidentService.resolve(org.getId(), id);
    }
}
