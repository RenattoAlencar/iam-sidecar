package com.development.iam.sidecar.route;

import com.development.iam.sidecar.config.InterceptRule;

import java.util.Optional;


public record RouteDecision(
        Outcome outcome,
        InterceptRule rule,
        String normalizedPath,
        RejectionReason rejectionReason
) {


    public enum Outcome {

        INTERCEPT,
        PASSTHROUGH,
        REJECT
    }

    public enum RejectionReason {

        MALFORMED_PATH
    }

    public RouteDecision {

        if (outcome == null) {
            throw new IllegalArgumentException("outcome é obrigatório");
        }
        switch (outcome) {
            case INTERCEPT -> {
                requirePresent(rule, "rule", Outcome.INTERCEPT);
                requirePresent(normalizedPath, "normalizedPath", Outcome.INTERCEPT);
                requireAbsent(rejectionReason, "rejectionReason", Outcome.INTERCEPT);
            }
            case PASSTHROUGH -> {
                requirePresent(normalizedPath, "normalizedPath", Outcome.PASSTHROUGH);
                requireAbsent(rule, "rule", Outcome.PASSTHROUGH);
                requireAbsent(rejectionReason, "rejectionReason", Outcome.PASSTHROUGH);
            }
            case REJECT -> {
                requirePresent(rejectionReason, "rejectionReason", Outcome.REJECT);
                requireAbsent(rule, "rule", Outcome.REJECT);
                requireAbsent(normalizedPath, "normalizedPath", Outcome.REJECT);
            }
        }
    }

    private static void requirePresent(Object value, String field, Outcome outcome) {

        if (value == null) {
            throw new IllegalArgumentException(
                    field + " é obrigatório quando o desfecho é " + outcome);
        }
    }

    private static void requireAbsent(Object value, String field, Outcome outcome) {

        if (value != null) {
            throw new IllegalArgumentException(
                    field + " não se aplica quando o desfecho é " + outcome);
        }
    }

    public static RouteDecision intercept(InterceptRule rule, String normalizedPath) {
        return new RouteDecision(Outcome.INTERCEPT, rule, normalizedPath, null);
    }

    public static RouteDecision passthrough(String normalizedPath) {
        return new RouteDecision(Outcome.PASSTHROUGH, null, normalizedPath, null);
    }

    public static RouteDecision reject(RejectionReason rejectionReason) {
        return new RouteDecision(Outcome.REJECT, null, null, rejectionReason);
    }

    public Optional<InterceptRule> matchedRule() {
        return Optional.ofNullable(rule);
    }


    public String metricTag() {
        return rule != null ? rule.name() : outcome.name().toLowerCase();
    }
}