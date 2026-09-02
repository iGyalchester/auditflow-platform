package com.auditflow.alerting.rules;

import com.auditflow.common.model.AlertRule;

import java.util.List;

/**
 * Where a customer's alert rules come from. The seam that lets the source
 * change (file today, Aurora behind the gateway's rule API later) without
 * touching the dispatcher.
 */
public interface RuleRepository {

    /** Rules for one customer, enabled or not - the engine checks enabled. */
    List<AlertRule> rulesFor(String customerId);
}
