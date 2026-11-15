package com.project.dacn.dto.request.catalog;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Shape validation only; the combined 1-100 unique count is a service rule. */
class StockSnapshotRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void nullArraysAreRejected() {
        assertThat(properties(new StockSnapshotRequest(null, null)))
                .containsExactlyInAnyOrder("productIds", "variantIds");
    }

    @Test
    void emptyArraysPassShapeValidation() {
        assertThat(violations(new StockSnapshotRequest(List.of(), List.of()))).isEmpty();
    }

    @Test
    void nullIdElementsAreRejected() {
        assertThat(properties(new StockSnapshotRequest(Arrays.asList("PRD_1", null), List.of())))
                .contains("productIds");
        assertThat(properties(new StockSnapshotRequest(List.of(), Arrays.asList("VAR_1", null))))
                .contains("variantIds");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void blankIdElementsAreRejected(String blank) {
        assertThat(properties(new StockSnapshotRequest(List.of(blank), List.of())))
                .contains("productIds");
        assertThat(properties(new StockSnapshotRequest(List.of(), List.of(blank))))
                .contains("variantIds");
    }

    @Test
    void idLengthBoundaryIsTwoHundredFiftyFiveCharacters() {
        assertThat(violations(new StockSnapshotRequest(List.of("x".repeat(255)), List.of()))).isEmpty();
        assertThat(properties(new StockSnapshotRequest(List.of("x".repeat(256)), List.of())))
                .contains("productIds");
        assertThat(properties(new StockSnapshotRequest(List.of(), List.of("x".repeat(256)))))
                .contains("variantIds");
    }

    @Test
    void oneHundredIdsPerArrayPassShapeValidation() {
        assertThat(violations(new StockSnapshotRequest(ids(100), ids(100)))).isEmpty();
    }

    private static List<String> ids(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add("ID_" + i);
        }
        return ids;
    }

    private static List<String> properties(StockSnapshotRequest request) {
        return violations(request).stream()
                .map(violation -> violation.getPropertyPath().iterator().next().getName())
                .toList();
    }

    private static Set<ConstraintViolation<StockSnapshotRequest>> violations(StockSnapshotRequest request) {
        return VALIDATOR.validate(request);
    }
}
