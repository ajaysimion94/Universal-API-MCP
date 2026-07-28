package com.mcpserver.reports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RqlValuesTests {

    @Test
    void fingerprintIgnoresObjectInsertionOrderRecursively() {
        Map<String, Object> firstNested = new LinkedHashMap<>();
        firstNested.put("status", "open");
        firstNested.put("count", new BigDecimal("2.00"));
        Map<String, Object> secondNested = new LinkedHashMap<>();
        secondNested.put("count", 2);
        secondNested.put("status", "open");

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", 7);
        first.put("details", firstNested);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("details", secondNested);
        second.put("id", new BigDecimal("7.0"));

        assertThat(RqlValues.fingerprint(first)).isEqualTo(RqlValues.fingerprint(second));
    }

    @Test
    void fingerprintPreservesValueBoundariesAndListOrder() {
        Map<String, Object> first = Map.of("a", "x", "b", List.of("y", "z"));
        Map<String, Object> differentBoundaries = Map.of("a", "x\u0001b=y", "b", List.of("z"));
        Map<String, Object> differentListOrder = Map.of("a", "x", "b", List.of("z", "y"));

        assertThat(RqlValues.fingerprint(first))
                .isNotEqualTo(RqlValues.fingerprint(differentBoundaries))
                .isNotEqualTo(RqlValues.fingerprint(differentListOrder));
    }
}
