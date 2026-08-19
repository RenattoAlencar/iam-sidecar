package com.development.iam.sidecar.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpMethod;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record InterceptRule(

        @NotBlank(message = "name da regra de interceptação é obrigatório")
        String name,

        @NotBlank(message = "path da regra de interceptação é obrigatório")
        String path,

        @NotEmpty(message = "methods da regra de interceptação deve conter ao menos um método")
        Set<HttpMethod> methods
) {

    public InterceptRule {

        methods = methods == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(methods));
    }


    public Set<String> identityKeys() {

        Set<String> keys = new LinkedHashSet<>();
        methods.forEach(method -> keys.add(method.name() + " " + path));
        return keys;
    }

    @Override
    public String toString() {
        return "%s [%s %s]".formatted(name, methods, path);
    }
}