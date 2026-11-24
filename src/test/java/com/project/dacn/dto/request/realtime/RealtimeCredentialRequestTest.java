package com.project.dacn.dto.request.realtime;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeCredentialRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        factory.close();
    }

    @Test
    void rejectsNullProductIds() {
        assertThat(validator.validate(new RealtimeCredentialRequest(null))).hasSize(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsNullOrBlankProductId(String id) {
        List<String> ids = new ArrayList<>();
        ids.add(id);

        assertThat(validator.validate(new RealtimeCredentialRequest(ids))).hasSize(1);
    }

    @Test
    void rejectsProductIdOver255Characters() {
        assertThat(validator.validate(new RealtimeCredentialRequest(List.of("x".repeat(256)))))
                .hasSize(1);
    }

    @Test
    void acceptsProductIdAt255CharactersWithoutRewritingIt() {
        String id = " " + "x".repeat(253) + " ";
        var request = new RealtimeCredentialRequest(List.of(id));

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.productIds()).containsExactly(id);
    }

    @Test
    void acceptsEmptyProductInterests() {
        assertThat(validator.validate(new RealtimeCredentialRequest(List.of()))).isEmpty();
    }

    @Test
    void accepts25RawEntriesWith24UniqueIdsAndPreservesRequestOrder() {
        List<String> ids = new ArrayList<>(IntStream.range(0, 24)
                .mapToObj(i -> "product-" + i).toList());
        ids.add(1, "product-0");
        var request = new RealtimeCredentialRequest(ids);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.productIds()).hasSize(25).containsExactlyElementsOf(ids);
        assertThat(request.productIds().subList(0, 3))
                .containsExactly("product-0", "product-0", "product-1");
    }
}
