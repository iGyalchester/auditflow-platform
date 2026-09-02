package com.auditflow.agent.collector;

import com.auditflow.common.interfaces.EventCollector;

/**
 * An {@link EventCollector} whose read position only advances when the
 * runner confirms the collected batch was delivered - the seam that makes
 * agent delivery at-least-once instead of lossy.
 */
public interface CommittableCollector extends EventCollector {

    /** Called by the runner after the last collected batch was published. */
    void commit();
}
