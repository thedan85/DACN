package com.project.dacn.common;

import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundMapsTo404Envelope() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleNotFound(new ResourceNotFoundException("category not found: x"));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("category not found: x");
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void conflictReturnsGenericMessage() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleConflict(new DataIntegrityViolationException(
                        "ERROR: duplicate key value violates unique constraint \"uq_categories_slug\""));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getMessage()).doesNotContain("uq_categories_slug");
    }

    @Test
    void illegalArgumentMapsTo400Envelope() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("bad value"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }

    @Test
    void constraintViolationMapsTo400Envelope() {
        ResponseEntity<ApiResponse<?>> response = handler
                .handleConstraintViolation(new ConstraintViolationException("violated", Collections.emptySet()));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }

    @Test
    void validationFailureMapsTo400Envelope() {
        org.springframework.validation.BeanPropertyBindingResult bindingResult =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "target");
        bindingResult.reject("NotBlank", "must not be blank");
        ResponseEntity<ApiResponse<?>> response = handler.handleValidation(
                new MethodArgumentNotValidException(
                        new org.springframework.core.MethodParameter(
                                CatalogExceptionHandlerTest.class.getDeclaredMethods()[0], -1),
                        bindingResult));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }

    @Test
    void genericExceptionReturnsGenericMessage() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleGeneric(new RuntimeException("boom"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getCode()).isEqualTo(500);
        assertThat(response.getBody().getMessage()).doesNotContain("boom");
    }

    @Test
    void unknownRouteMapsTo404Envelope() {
        ResponseEntity<ApiResponse<?>> response = handler.handleNoResource(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/api/nope", "/api/nope"));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getCode()).isEqualTo(404);
    }

    @Test
    void malformedJsonMapsTo400Envelope() {
        ResponseEntity<ApiResponse<?>> response = handler.handleNotReadable(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad json", null, null));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }

    @Test
    void oversizeUploadMapsTo400Envelope() {
        ResponseEntity<ApiResponse<?>> response = handler.handleOversize(
                new org.springframework.web.multipart.MaxUploadSizeExceededException(10L * 1024 * 1024));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }

    @Test
    void missingFilePartMapsTo400Envelope() {
        ResponseEntity<ApiResponse<?>> response = handler.handleMissingPart(
                new org.springframework.web.multipart.support.MissingServletRequestPartException("file"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }
}
