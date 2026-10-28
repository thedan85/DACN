package com.project.dacn.security;

import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.identity.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Resolves the customer row from the authenticated principal. Never trusts a
 * client-supplied customer id.
 */
@Component
@RequiredArgsConstructor
public class CustomerIdentity {

    private final CustomerRepository customerRepository;

    public Long requireCustomerId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("authentication required");
        }
        return customerRepository.findByUser_Email(auth.getName())
                .map(Customer::getId)
                .orElseThrow(() -> new AccessDeniedException("customer profile not found"));
    }
}
