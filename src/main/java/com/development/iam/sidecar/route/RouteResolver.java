package com.development.iam.sidecar.route;

import com.development.iam.sidecar.config.InterceptRule;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RouteResolver {

    private static final PathPatternParser PATTERN_PARSER = new PathPatternParser();

    private final List<CompiledRule> compiledRules;

    public RouteResolver(List<InterceptRule> rules) {
        List<CompiledRule> compiled = new ArrayList<>();

        for (InterceptRule rule : rules) {
            try {
                compiled.add(new CompiledRule(rule, PATTERN_PARSER.parse(rule.path())));
            } catch (PatternParseException e) {
                throw new IllegalArgumentException(
                        "Padrão de path inválido na regra '" + rule.name() + "'", e);
            }
        }
        this.compiledRules = List.copyOf(compiled);
    }

    public RouteDecision resolve(String rawPath, HttpMethod method) {

        if (method == null) {
            return RouteDecision.reject(RouteDecision.RejectionReason.MALFORMED_PATH);
        }

        Optional<String> normalized = PathNormalizer.normalize(rawPath);

        if (normalized.isEmpty()) {
            return RouteDecision.reject(RouteDecision.RejectionReason.MALFORMED_PATH);
        }

        String path = normalized.get();

        return matchingRule(path, method)
                .map(rule -> RouteDecision.intercept(rule, path))
                .orElseGet(() -> RouteDecision.passthrough(path));
    }

    private Optional<InterceptRule> matchingRule(String path, HttpMethod method) {
        PathContainer container = PathContainer.parsePath(path);

        for (CompiledRule compiled : compiledRules) {
            InterceptRule rule = compiled.rule();

            if (rule.methods().contains(method) && compiled.pattern().matches(container)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private record CompiledRule(InterceptRule rule, PathPattern pattern) {
    }
}