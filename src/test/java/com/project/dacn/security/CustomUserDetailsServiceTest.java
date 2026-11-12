package com.project.dacn.security;

import com.project.dacn.entity.auth.Permission;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.RolePermission;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.auth.PermissionRepository;
import com.project.dacn.repository.auth.RolePermissionRepository;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(CustomUserDetailsService.class)
class CustomUserDetailsServiceTest {

    @Autowired
    CustomUserDetailsService service;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    PermissionRepository permissionRepository;
    @Autowired
    RolePermissionRepository rolePermissionRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CustomerRepository customerRepository;
    @Autowired
    EntityManager entityManager;

    @Test
    void loadsAuthoritiesAndCustomerInOneQuery() {
        Role role = roleRepository.save(Role.builder().code("customer").name("Customer").build());
        Permission permission = permissionRepository.save(Permission.builder()
                .code("product:read").name("Read products")
                .resource("product").action("read").build());
        rolePermissionRepository.save(RolePermission.builder()
                .role(role).permission(permission).build());
        User user = userRepository.save(User.builder()
                .email("jwt@example.com").passwordHash("hash").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("JWT Customer").phone("0901234567").address("addr").build());

        Long customerDbId = customer.getId();
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        UserDetails details = service.loadUserByUserId(user.getUserId());

        assertThat(details).isInstanceOf(AuthenticatedUser.class);
        AuthenticatedUser principal = (AuthenticatedUser) details;
        assertThat(principal.getUsername()).isEqualTo(user.getUserId());
        assertThat(principal.email()).isEqualTo("jwt@example.com");
        assertThat(principal.name()).isEqualTo("JWT Customer");
        assertThat(principal.phone()).isEqualTo("0901234567");
        assertThat(principal.address()).isEqualTo("addr");
        assertThat(principal.roleCode()).isEqualTo("customer");
        assertThat(principal.roleName()).isEqualTo("Customer");
        assertThat(principal.permissions()).containsExactly("product:read");
        assertThat(principal.hasPassword()).isTrue();
        assertThat(principal.customerId()).isEqualTo(customerDbId);
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("product:read");
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }
}
