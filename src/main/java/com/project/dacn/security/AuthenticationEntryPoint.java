package com.project.dacn.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.dacn.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class AuthenticationEntryPoint implements org.springframework.security.web.AuthenticationEntryPoint {

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");

        String message = messageSource.getMessage("error.unauthorized", null,
                "Unauthorized", LocaleContextHolder.getLocale());
        ApiResponse<?> body = ApiResponse.of(401, message, null);

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
