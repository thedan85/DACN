package com.project.dacn.controller.test;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test controller for RBAC integration testing.
 * Located in src/test to avoid polluting production code.
 */
@RestController
@RequestMapping("/api/v1/test")
public class TestProtectedController {

    @GetMapping("/public")
    public String publicEndpoint() {
        return "public";
    }

    @GetMapping("/protected")
    @PreAuthorize("hasAuthority('product:create')")
    public String protectedEndpoint() {
        return "protected";
    }
}
