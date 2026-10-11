package com.project.dacn.repository.auth;

import com.project.dacn.entity.auth.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByPermissionId(String permissionId);

    Optional<Permission> findByCode(String code);
}
