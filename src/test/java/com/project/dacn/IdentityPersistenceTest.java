package com.project.dacn;

import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.entity.identity.Admin;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.identity.Employee;
import com.project.dacn.repository.identity.AdminRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.identity.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class IdentityPersistenceTest {

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AdminRepository adminRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    EmployeeRepository employeeRepository;

    private User newUser(String email, String roleCode) {
        Role role = roleRepository.save(Role.builder().code(roleCode).name(roleCode).build());
        return userRepository.save(User.builder().email(email).passwordHash("h").role(role).build());
    }

    @Test
    void persistProfilesThroughBusinessKeyFk() {
        Admin admin = adminRepository.save(Admin.builder()
                .user(newUser("a1@x.com", "r1")).name("A1").build());
        assertThat(admin.getAdminId()).isNotNull();

        Customer customer = customerRepository.save(Customer.builder()
                .user(newUser("c1@x.com", "r2")).name("C1").phone("090").build());
        assertThat(customer.getCustomerId()).isNotNull();
        assertThat(customerRepository.findByCustomerId(customer.getCustomerId())).isPresent();

        Employee emp = employeeRepository.save(Employee.builder()
                .user(newUser("e1@x.com", "r3")).name("E1").phone("091").build());
        assertThat(emp.getEmployeeId()).isNotNull();
    }
}
