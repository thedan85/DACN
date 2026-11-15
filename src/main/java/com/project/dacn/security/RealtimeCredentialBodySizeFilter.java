package com.project.dacn.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RealtimeCredentialBodySizeFilter extends BoundedBodySizeFilter {

    public RealtimeCredentialBodySizeFilter(ObjectMapper mapper) {
        super(mapper, 32768, "/api/v1/realtime/credentials");
    }
}
