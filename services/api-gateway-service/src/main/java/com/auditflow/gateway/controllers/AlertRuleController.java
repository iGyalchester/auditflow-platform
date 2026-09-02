package com.auditflow.gateway.controllers;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.rules.ConditionEvaluator;
import com.auditflow.gateway.data.AlertRuleRepository;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
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
            String conditionExpression,
            Boolean enabled,
            List<String> notificationChannels) {
    }

    private final AlertRuleRepository repository;
    private final RequestScope scope;
    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    public AlertRuleController(AlertRuleRepository repository, RequestScope scope) {
        this.repository = repository;
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

    @PutMapping("/{ruleId}")
    public AlertRule replace(HttpServletRequest request, @PathVariable("ruleId") String ruleId,
                             @Valid @RequestBody AlertRuleRequest body) {
        String customerId = scope.customerId(request);
        if (repository.find(customerId, ruleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such rule");
        }
        AlertRule rule = toRule(customerId, ruleId, body);
        repository.upsert(rule);
        return rule;
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable("ruleId") String ruleId) {
        if (!repository.delete(scope.customerId(request), ruleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such rule");
        }
        return ResponseEntity.noContent().build();
    }

    private AlertRule toRule(String customerId, String ruleId, AlertRuleRequest body) {
        String problem = evaluator.validate(body.conditionExpression());
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        }
        List<String> channels = body.notificationChannels() == null ? List.of() : body.notificationChannels();
        for (String channel : channels) {
            if (!KNOWN_CHANNELS.contains(channel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unknown notification channel '" + channel + "' (known: " + KNOWN_CHANNELS + ")");
            }
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
                .notificationChannels(channels)
                .build();
    }
}
