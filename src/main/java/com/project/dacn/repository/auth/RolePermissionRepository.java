package com.project.dacn.repository.auth;

import com.project.dacn.entity.auth.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    Optional<RolePermission> findByRolePermissionId(String rolePermissionId);

    List<RolePermission> findByRole_RoleId(String roleId);

    @Query("SELECT rp FROM RolePermission rp JOIN FETCH rp.permission WHERE rp.role.roleId = :roleId")
    List<RolePermission> findByRole_RoleIdWithPermission(@Param("roleId") String roleId);
}
