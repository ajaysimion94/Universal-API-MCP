package com.mcpserver.workflow;

import java.util.Map;

/**
 * The GUARD seam in the §7.2 workflow; pass-through until Phase 6.
 */
public interface EntitlementChecker {
    void check(String actor, String toolId, Map<String, Object> params) throws SecurityException;
}
