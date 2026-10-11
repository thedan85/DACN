package com.project.dacn.modules.identity.repository;

import com.project.dacn.modules.identity.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByCustomerId(String customerId);

    Optional<Customer> findByUser_UserId(String userId);
}
