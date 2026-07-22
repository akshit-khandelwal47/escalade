package dev.escalade.incident;

import dev.escalade.common.ConflictException;
import dev.escalade.common.NotFoundException;
import dev.escalade.incident.IncidentDtos.AttemptResponse;
import dev.escalade.incident.IncidentDtos.CreateIncidentRequest;
import dev.escalade.incident.IncidentDtos.IncidentResponse;
import dev.escalade.policy.EscalationStep;
import dev.escalade.policy.PolicyRepository;
import dev.escalade.policy.StepRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final int MAX_TRANSITION_ATTEMPTS = 3;

    private final PolicyRepository policies;
    private final StepRepository steps;
    private final IncidentRepository incidents;
    private final NotificationAttemptRepository attempts;
    private final IncidentWriter writer;

    public IncidentService(PolicyRepository policies, StepRepository steps, IncidentRepository incidents,
            NotificationAttemptRepository attempts, IncidentWriter writer) {
        this.policies = policies;
        this.steps = steps;
        this.incidents = incidents;
        this.attempts = attempts;
        this.writer = writer;
    }

    /** Result of a create call: whether a new incident was created or an existing one was deduplicated. */
    public record CreateResult(IncidentResponse incident, boolean created) {}

    /**
     * Idempotent create keyed by (org, dedup_key). A retried webhook for the same ongoing
     * outage returns the existing OPEN incident instead of paging again. The partial unique
     * index is the real guarantee; the pre-check is just the fast, common path.
     */
    public CreateResult create(UUID orgId, CreateIncidentRequest req) {
        policies.findByIdAndOrgId(req.policyId(), orgId)
                .orElseThrow(() -> new NotFoundException("Policy not found: " + req.policyId()));

        var existing = incidents.findByOrgIdAndDedupKeyAndStatus(orgId, req.dedupKey(), IncidentStatus.OPEN);
        if (existing.isPresent()) {
            return new CreateResult(toResponse(existing.get()), false);
        }

        List<EscalationStep> policySteps = steps.findByPolicyIdOrderByStepOrder(req.policyId());
        try {
            Incident created = writer.insertIncidentWithFirstAttempt(orgId, req, policySteps);
            return new CreateResult(toResponse(created), true);
        } catch (DataIntegrityViolationException race) {
            // Lost the race to a concurrent create with the same dedup key — return the winner.
            Incident winner = incidents.findByOrgIdAndDedupKeyAndStatus(orgId, req.dedupKey(), IncidentStatus.OPEN)
                    .orElseThrow(() -> race);
            return new CreateResult(toResponse(winner), false);
        }
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(UUID orgId, UUID id) {
        return toResponse(load(orgId, id));
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> list(UUID orgId, IncidentStatus status) {
        List<Incident> found = (status == null)
                ? incidents.findByOrgIdOrderByCreatedAtDesc(orgId)
                : incidents.findByOrgIdAndStatusOrderByCreatedAtDesc(orgId, status);
        return found.stream().map(this::toResponse).toList();
    }

    /**
     * Acknowledging races the escalation worker: the on-call may hit ack in the same instant the
     * worker is advancing to the next step. The loser of that race is detected by the incident's
     * optimistic-lock version, and retrying is the correct response — on the retry we re-read the
     * incident (now one step further along, still OPEN) and acknowledge it for real, cancelling the
     * step the worker just scheduled. Without this the on-call would see a 500 and keep getting paged.
     */
    public IncidentResponse acknowledge(UUID orgId, UUID id) {
        return toResponse(withRetryOnCollision(() -> writer.acknowledge(orgId, id)));
    }

    public IncidentResponse resolve(UUID orgId, UUID id) {
        return toResponse(withRetryOnCollision(() -> writer.resolve(orgId, id)));
    }

    private Incident withRetryOnCollision(Supplier<Incident> transition) {
        for (int attempt = 1; attempt <= MAX_TRANSITION_ATTEMPTS; attempt++) {
            try {
                return transition.get();
            } catch (ObjectOptimisticLockingFailureException collision) {
                log.info("incident {} was modified concurrently, retrying transition ({}/{})",
                        id(collision), attempt, MAX_TRANSITION_ATTEMPTS);
            }
        }
        throw new ConflictException("Incident is being modified concurrently; please retry");
    }

    private static Object id(ObjectOptimisticLockingFailureException e) {
        return e.getIdentifier() != null ? e.getIdentifier() : "unknown";
    }

    private Incident load(UUID orgId, UUID id) {
        return incidents.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Incident not found: " + id));
    }

    private IncidentResponse toResponse(Incident i) {
        List<AttemptResponse> timeline = attempts.findByIncidentIdOrderByStepOrderAscCreatedAtAsc(i.getId()).stream()
                .map(a -> new AttemptResponse(a.getStepOrder(), a.getChannel(), a.getTarget(), a.getStatus(),
                        a.getAttemptCount(), a.getDueAt(), a.getSentAt()))
                .toList();
        return new IncidentResponse(i.getId(), i.getPolicyId(), i.getTitle(), i.getDedupKey(), i.getStatus(),
                i.getCurrentStep(), i.getCreatedAt(), i.getAckedAt(), i.getResolvedAt(), timeline);
    }
}
