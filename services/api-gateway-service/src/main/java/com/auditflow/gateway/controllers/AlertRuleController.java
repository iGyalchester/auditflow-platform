package com.auditflow.gateway.controllers;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControls;
import com.auditflow.common.rules.ConditionEvaluator;
import com.auditflow.common.rules.RuleMatcher;
import com.auditflow.gateway.api.RuleCheck;
import com.auditflow.gateway.data.AlertRuleRepository;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Customers manage their own alert rules here. The rule's customer is
 * always the caller's; the id is server-generated on create. A condition
 * is validated with the same sandboxed evaluator alerting uses, so a rule
 * that cannot run is rejected with a 400 instead of silently never firing.
 * alerting-service picks changes up within its refresh interval.
 */
@RestController
@RequestMapping("/api/v1/alert-rules")
public class AlertRuleController {

    /** Channels alerting-service has notifiers for. */
    static final Set<String> KNOWN_CHANNELS = Set.of("slack", "email");

    public record AlertRuleRequest(
            @NotBlank @Size(max = 255) String name,
            String description,
            EventType eventType,
            RiskLevel riskThreshold,
            @Size(max = ConditionEvaluator.MAX_EXPRESSION_LENGTH) String conditionExpression,
            Boolean enabled,
            List<String> notificationChannels) {
    }

    /** A dry run evaluates at most this many events; above it the window must be narrowed. */
    static final int MAX_DRY_RUN_EVENTS = 10_000;
    static final int DRY_RUN_SAMPLE = 5;
    static final Duration DRY_RUN_DEFAULT_WINDOW = Duration.ofDays(7);

    private final AlertRuleRepository repository;
    private final AuditLogRepository auditLogs;
    private final RequestScope scope;
    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final RuleMatcher matcher = new RuleMatcher(evaluator);

    public AlertRuleController(AlertRuleRepository repository, AuditLogRepository auditLogs, RequestScope scope) {
        this.repository = repository;
        this.auditLogs = auditLogs;
        this.scope = scope;
    }

    @GetMapping
    public List<AlertRule> list(HttpServletRequest request) {
        return repository.findAll(scope.customerId(request));
    }

    @GetMapping("/{ruleId}")
    public AlertRule get(HttpServletRequest request, @PathVariable("ruleId") String ruleId) {
        return repository.find(scope.customerId(request), ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such rule"));
    }

    @PostMapping
    public ResponseEntity<AlertRule> create(HttpServletRequest request, @Valid @RequestBody AlertRuleRequest body) {
        AlertRule rule = toRule(scope.customerId(request), UUID.randomUUID().toString(), body);
        repository.upsert(rule);
        return ResponseEntity.created(URI.create("/api/v1/alert-rules/" + rule.getRuleId())).body(rule);
    }

    /**
     * One statement, not a SELECT then a write: the row count answers the
     * same question the SELECT was asking, and there is no window in which
     * the rule is deleted in between.
     *
     * <p>An UPDATE rather than an upsert, so PUT cannot create. Ids are
     * server-generated on POST; letting a PUT to an unknown id insert would
     * hand clients the choice of id in a globally unique namespace and turn
     * a typo'd path into a new rule.
     */
    @PutMapping("/{ruleId}")
    public AlertRule replace(HttpServletRequest request, @PathVariable("ruleId") String ruleId,
                             @Valid @RequestBody AlertRuleRequest body) {
        AlertRule rule = toRule(scope.customerId(request), ruleId, body);
        if (repository.update(rule) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such rule");
        }
        return rule;
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable("ruleId") String ruleId) {
        if (!repository.delete(scope.customerId(request), ruleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such rule");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * "Is this condition acceptable?" without saving anything - the editor
     * asks on every pause in typing. A 200 either way; the answer is in the
     * body, because an invalid draft is the expected case, not an error.
     */
    @PostMapping("/validate")
    public RuleCheck.Validation validate(HttpServletRequest request, @Valid @RequestBody RuleCheck.Draft draft) {
        scope.customerId(request);
        String problem = evaluator.validate(draft.conditionExpression());
        return new RuleCheck.Validation(problem == null, problem);
    }

    /**
     * "How often would this have fired?" - the draft is evaluated, with the
     * same matcher alerting-service uses, over the customer's events in
     * the window (default: the last seven days). Capped at
     * {@value #MAX_DRY_RUN_EVENTS} events, 413 above that, like reports:
     * the answer for a bigger window is to narrow it, not to wait.
     */
    @PostMapping("/dry-run")
    public RuleCheck.DryRun dryRun(HttpServletRequest request, @Valid @RequestBody RuleCheck.Draft draft,
                                   @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        String customerId = scope.customerId(request);
        TimeWindow window = TimeWindow.resolve(from, to, DRY_RUN_DEFAULT_WINDOW, StatsController.MAX_WINDOW);
        String problem = evaluator.validate(draft.conditionExpression());
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        }
        List<AuditEvent> events = auditLogs.findEvents(customerId, window.from(), window.to(), MAX_DRY_RUN_EVENTS + 1);
        if (events.size() > MAX_DRY_RUN_EVENTS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "window has more than " + MAX_DRY_RUN_EVENTS + " events; narrow it");
        }
        AlertRule candidate = AlertRule.builder()
                .ruleId("dry-run").customerId(customerId).name("dry-run").enabled(true)
                .eventType(draft.eventType()).riskThreshold(draft.riskThreshold())
                .conditionExpression(draft.conditionExpression() == null || draft.conditionExpression().isBlank()
                        ? null : draft.conditionExpression().trim())
                .build();
        int matched = 0;
        List<AuditLogRow> sample = new ArrayList<>();
        for (AuditEvent event : events) {
            if (matcher.matches(candidate, event)) {
                matched++;
                if (sample.size() < DRY_RUN_SAMPLE) {
                    sample.add(toRow(event));
                }
            }
        }
        return new RuleCheck.DryRun(window.from(), window.to(), events.size(), matched, sample);
    }

    static AuditLogRow toRow(AuditEvent event) {
        return new AuditLogRow(event.getEventId(), event.getUserId(), event.getSessionId(), event.getTimestamp(),
                event.getType() == null ? null : event.getType().name(), event.getResource(), event.getAction(),
                event.getRiskLevel() == null ? null : event.getRiskLevel().name(), event.isAnomalous(),
                ComplianceControls.encode(event.getControls()));
    }

    private AlertRule toRule(String customerId, String ruleId, AlertRuleRequest body) {
        String problem = evaluator.validate(body.conditionExpression());
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        }
        // A LinkedHashSet keeps the caller's order, drops repeats, and bounds
        // what is joined into notification_channels VARCHAR(255) - two known
        // channels cannot overflow it however many times they are sent.
        Set<String> channels = new LinkedHashSet<>();
        for (String channel : body.notificationChannels() == null ? List.<String>of() : body.notificationChannels()) {
            // KNOWN_CHANNELS is a Set.of, whose contains(null) throws rather
            // than returning false - a null element used to be a 500
            if (channel == null || !KNOWN_CHANNELS.contains(channel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unknown notification channel '" + channel + "' (known: " + KNOWN_CHANNELS + ")");
            }
            channels.add(channel);
        }
        return AlertRule.builder()
                .ruleId(ruleId)
                .customerId(customerId)
                .name(body.name().trim())
                .description(body.description())
                .eventType(body.eventType())
                .riskThreshold(body.riskThreshold())
                .conditionExpression(body.conditionExpression() == null || body.conditionExpression().isBlank()
                        ? null : body.conditionExpression().trim())
                .enabled(body.enabled() == null || body.enabled())
                .notificationChannels(List.copyOf(channels))
                .build();
    }
}
