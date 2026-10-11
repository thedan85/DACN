package com.project.dacn.repository.identity;

import com.project.dacn.entity.identity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByCustomerId(String customerId);

    Optional<Customer> findByUser_UserId(String userId);
}
