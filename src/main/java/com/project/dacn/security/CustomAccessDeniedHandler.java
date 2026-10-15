package com.project.dacn.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.dacn.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");

        String message = messageSource.getMessage("error.forbidden", null,
                "Access denied", LocaleContextHolder.getLocale());
        ApiResponse<?> body = ApiResponse.of(403, message, null);

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
