package com.project.dacn.repository;

import com.project.dacn.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    Optional<RolePermission> findByRolePermissionId(String rolePermissionId);

    List<RolePermission> findByRole_RoleId(String roleId);
}
