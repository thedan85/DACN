package com.project.dacn.modules.auth.repository;

import com.project.dacn.modules.auth.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByPermissionId(String permissionId);

    Optional<Permission> findByCode(String code);
}
