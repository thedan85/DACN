package com.project.dacn.repository.identity;

import com.project.dacn.entity.identity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByEmployeeId(String employeeId);

    Optional<Employee> findByUser_UserId(String userId);
}
