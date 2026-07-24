package com.mcpserver.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Placeholder until Phase 6 activates real enforcement.
 */
@Component
public class PassThroughEntitlementChecker implements EntitlementChecker {
    private static final Logger log = LoggerFactory.getLogger(PassThroughEntitlementChecker.class);

    @Override
    public void check(String actor, String toolId, Map<String, Object> params) throws SecurityException {
        log.debug("Passing through entitlement check for actor {} on toolId {}", actor, toolId);
    }
}
