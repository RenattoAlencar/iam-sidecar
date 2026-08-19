package com.development.iam.sidecar.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class InterceptRuleTest {

    private static final String NAME = "pix-transfer";
    private static final String PATH = "/api/pix/**";

    private static InterceptRule rule(Set<HttpMethod> methods) {
        return new InterceptRule(NAME, PATH, methods);
    }

    @Test
    @DisplayName("o conjunto de métodos é imutável")
    void methodsAreImmutable() {
        InterceptRule rule = rule(Set.of(HttpMethod.POST));

        assertThatThrownBy(() -> rule.methods().add(HttpMethod.GET))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a regra não compartilha a coleção recebida")
    void doesNotShareIncomingCollection() {
        Set<HttpMethod> incoming = new LinkedHashSet<>(Set.of(HttpMethod.POST));
        InterceptRule rule = rule(incoming);

        incoming.add(HttpMethod.DELETE);

        assertThat(rule.methods()).containsExactly(HttpMethod.POST);
    }

    @Test
    @DisplayName("a ordem declarada dos métodos é preservada")
    void preservesDeclaredMethodOrder() {
        Set<HttpMethod> declared = new LinkedHashSet<>();
        declared.add(HttpMethod.PUT);
        declared.add(HttpMethod.POST);
        declared.add(HttpMethod.PATCH);

        assertThat(rule(declared).methods())
                .containsExactly(HttpMethod.PUT, HttpMethod.POST, HttpMethod.PATCH);
    }

    @Test
    @DisplayName("métodos nulos viram conjunto vazio, sem estourar")
    void nullMethodsBecomeEmptySet() {
        assertThat(rule(null).methods()).isEmpty();
    }

    @Test
    @DisplayName("gera uma chave de identidade por método")
    void generatesOneIdentityKeyPerMethod() {
        Set<HttpMethod> declared = new LinkedHashSet<>();
        declared.add(HttpMethod.POST);
        declared.add(HttpMethod.PUT);

        assertThat(rule(declared).identityKeys())
                .containsExactly("POST " + PATH, "PUT " + PATH);
    }

    @Test
    @DisplayName("a representação textual carrega o que a auditoria precisa")
    void textualFormCarriesAuditableFields() {
        assertThat(rule(Set.of(HttpMethod.POST)).toString())
                .contains(NAME)
                .contains(PATH)
                .contains(HttpMethod.POST.name());
    }
}