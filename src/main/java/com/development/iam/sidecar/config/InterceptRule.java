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


    /**
     * Chaves de identidade da regra, <strong>uma por método</strong>.
     * <p>
     * Usadas apenas para detectar sobreposição na validação de boot. A chave
     * combina método e path, e não só o path, porque duas regras no mesmo
     * endereço com métodos disjuntos são legítimas — é o caso de
     * {@code GET /pix/chaves} sendo passthrough e {@code POST} exigindo
     * confirmação.
     * <p>
     * Reduzir para uma chave por regra recusaria exatamente essa configuração,
     * que é o caso de uso principal da matriz.
     */
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