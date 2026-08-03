package com.mcpserver.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpserver.learning.LearningModel.PolicyArm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class PolicyArmRepository {

    private static final Logger log = LoggerFactory.getLogger(PolicyArmRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<PolicyArm> MAPPER = (rs, rowNum) -> new PolicyArm(
            rs.getString("arm_id"),
            rs.getFloat("w_vector"),
            rs.getFloat("w_lexical"),
            parseMatrix(rs.getString("a_matrix")),
            parseVector(rs.getString("b_vector")),
            rs.getInt("pulls"),
            rs.getDouble("reward_sum"),
            rs.getInt("enabled") != 0);

    private final JdbcTemplate jdbc;

    public PolicyArmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PolicyArm> findAll() {
        try {
            return jdbc.query("SELECT * FROM ranking_policy_arms ORDER BY rowid", MAPPER);
        } catch (Exception exception) {
            // No arms means the policy serves the baseline blend — always a safe answer.
            log.warn("Policy arm read failed: {}", exception.getMessage());
            return List.of();
        }
    }

    public void save(PolicyArm arm) {
        jdbc.update("""
                        INSERT OR REPLACE INTO ranking_policy_arms (
                            arm_id, w_vector, w_lexical, a_matrix, b_vector,
                            pulls, reward_sum, enabled, updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?)""",
                arm.armId(),
                arm.wVector(),
                arm.wLexical(),
                toJson(arm.a()),
                toJson(arm.b()),
                arm.pulls(),
                arm.rewardSum(),
                arm.enabled() ? 1 : 0,
                Instant.now().toString());
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM ranking_policy_arms");
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    /** Corrupt learned state reads back as null, which the policy re-seeds to the identity prior. */
    private static double[][] parseMatrix(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return JSON.readValue(value, double[][].class);
        } catch (Exception exception) {
            return null;
        }
    }

    private static double[] parseVector(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return JSON.readValue(value, double[].class);
        } catch (Exception exception) {
            return null;
        }
    }
}
