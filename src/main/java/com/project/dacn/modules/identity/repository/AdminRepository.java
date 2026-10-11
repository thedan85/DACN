package com.project.dacn.modules.identity.repository;

import com.project.dacn.modules.identity.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Optional<Admin> findByAdminId(String adminId);

    Optional<Admin> findByUser_UserId(String userId);
}
