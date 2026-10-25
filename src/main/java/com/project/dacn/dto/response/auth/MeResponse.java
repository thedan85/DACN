package com.project.dacn.dto.response.auth;

import java.util.List;

public record MeResponse(
        String userId,
        String email,
        String name,
        String phone,
        String address,
        String roleCode,
        String roleName,
        List<String> permissions
) {
}
