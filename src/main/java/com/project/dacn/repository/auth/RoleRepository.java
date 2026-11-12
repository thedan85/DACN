package com.project.dacn.repository.auth;

import com.project.dacn.entity.auth.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByRoleId(String roleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Role r WHERE r.roleId = :roleId")
    Optional<Role> findByRoleIdForUpdate(@Param("roleId") String roleId);

    Optional<Role> findByCode(String code);

    Optional<Role> findByCodeIgnoreCase(String code);

    @Query(value = "SELECT COUNT(*) FROM roles WHERE lower(role_code) = lower(:code)",
            nativeQuery = true)
    long countByCodeIgnoreCaseIncludingDeleted(@Param("code") String code);

    List<Role> findByDeletedFalse();

    List<Role> findByDeletedFalseOrderBySystemDescCodeAsc();
}
