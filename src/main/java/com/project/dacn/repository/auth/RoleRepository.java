package com.project.dacn.repository.auth;

import com.project.dacn.entity.auth.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByRoleId(String roleId);

    Optional<Role> findByCode(String code);

    Optional<Role> findByCodeIgnoreCase(String code);

    List<Role> findByDeletedFalse();
}
