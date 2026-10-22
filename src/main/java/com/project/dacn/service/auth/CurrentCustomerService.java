package com.project.dacn.service.auth;

import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.identity.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the authenticated user to their customer profile.
 *
 * <p>Identity always comes from the JWT-backed {@link SecurityContextHolder}, never from a
 * client-supplied header, so callers cannot impersonate another customer.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentCustomerService {

    private final CustomerRepository customerRepository;

    /**
     * @return the surrogate DB id of the customer linked to the current user
     * @throws InsufficientAuthenticationException when there is no authenticated user
     * @throws AccessDeniedException when the user authenticated but has no customer profile
     */
    public Long requireCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new InsufficientAuthenticationException("authentication required");
        }

        return customerRepository.findByUser_Email(auth.getName())
                .map(Customer::getId)
                .orElseThrow(() -> new AccessDeniedException(
                        "authenticated user has no customer profile"));
    }
}
