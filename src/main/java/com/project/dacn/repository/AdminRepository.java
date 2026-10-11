package com.project.dacn.repository;

import com.project.dacn.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Optional<Admin> findByAdminId(String adminId);

    Optional<Admin> findByUser_UserId(String userId);
}
