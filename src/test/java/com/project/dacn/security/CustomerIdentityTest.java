package com.project.dacn.security;

import com.project.dacn.repository.identity.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CustomerIdentityTest {

    @Mock
    CustomerRepository customerRepository;

    @InjectMocks
    CustomerIdentity identity;

    @Test
    void resolvesCustomerFromAuthenticatedPrincipalWithoutQuery() {
        AuthenticatedUser principal = new AuthenticatedUser(
                "USR_1", "jwt@example.com", "JWT Customer", "090", "addr",
                "customer", "Customer", List.of("product:read"), true,
                42L, "hash", true, true);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        assertThat(identity.requireCustomerId(authentication)).isEqualTo(42L);
        verifyNoInteractions(customerRepository);
    }
}
