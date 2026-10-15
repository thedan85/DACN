package com.project.dacn.common.exception;

import com.project.dacn.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired
    private MessageSource messageSource;

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(404)
                .body(ApiResponse.of(404, ex.getMessage(), null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(404)
                .body(ApiResponse.of(404, "resource not found", null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConflict(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return ResponseEntity.status(409)
                .body(ApiResponse.of(409, "resource already exists or is still referenced", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.of(400, ex.getMessage(), null));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.of(400, ex.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.of(400, ex.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.of(400, "malformed request body", null));
    }

    // Auth-specific exceptions
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<?>> handleEmailExists(EmailAlreadyExistsException ex) {
        String message = messageSource.getMessage("error.email.exists", null, 
                "Email already exists", LocaleContextHolder.getLocale());
        return ResponseEntity.status(409)
                .body(ApiResponse.of(409, message, null));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<?>> handleInvalidCredentials(InvalidCredentialsException ex) {
        String message = messageSource.getMessage("error.invalid.credentials", null,
                "Invalid credentials", LocaleContextHolder.getLocale());
        return ResponseEntity.status(401)
                .body(ApiResponse.of(401, message, null));
    }

    @ExceptionHandler(UserLockedException.class)
    public ResponseEntity<ApiResponse<?>> handleUserLocked(UserLockedException ex) {
        String message = messageSource.getMessage("error.user.locked", null,
                "User account is locked", LocaleContextHolder.getLocale());
        return ResponseEntity.status(403)
                .body(ApiResponse.of(403, message, null));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiResponse<?>> handleInvalidToken(InvalidTokenException ex) {
        String message = messageSource.getMessage("error.token.invalid", null,
                "Invalid or expired token", LocaleContextHolder.getLocale());
        return ResponseEntity.status(401)
                .body(ApiResponse.of(401, message, null));
    }

    // Security exceptions - prevent @PreAuthorize from becoming 500
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        // Distinguish between anonymous (401) and authenticated but lacking permission (403)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAnonymous = auth == null || !auth.isAuthenticated() 
                || auth instanceof AnonymousAuthenticationToken;
        
        if (isAnonymous) {
            // User not authenticated -> 401
            String message = messageSource.getMessage("error.unauthorized", null,
                    "Unauthorized", LocaleContextHolder.getLocale());
            return ResponseEntity.status(401)
                    .body(ApiResponse.of(401, message, null));
        } else {
            // User authenticated but lacks permission -> 403
            String message = messageSource.getMessage("error.forbidden", null,
                    "Access denied", LocaleContextHolder.getLocale());
            return ResponseEntity.status(403)
                    .body(ApiResponse.of(403, message, null));
        }
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<?>> handleAuthentication(AuthenticationException ex) {
        String message = messageSource.getMessage("error.unauthorized", null,
                "Unauthorized", LocaleContextHolder.getLocale());
        return ResponseEntity.status(401)
                .body(ApiResponse.of(401, message, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.of(500, "internal server error", null));
    }
}
