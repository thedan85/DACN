package com.project.dacn.repository.auth;

import com.project.dacn.entity.auth.User;
import com.project.dacn.common.constant.UserStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @EntityGraph(attributePaths = "role")
    Optional<User> findByUserId(String userId);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByGoogleSub(String googleSub);

    /**
     * One row per permission grant. Scalar projection rather than an entity
     * graph, so the principal reflects committed rows even when the caller's
     * persistence context already holds the same user and role.
     * Columns: userId, email, passwordHash, status, roleCode, roleName,
     * permission code, customer id, customer name, phone, address.
     */
    @Query("""
            select u.userId, u.email, u.passwordHash, u.status,
                   r.code, r.name, p.code,
                   c.id, c.name, c.phone, c.address
            from User u
            join u.role r
            left join r.permissions rp
            left join rp.permission p
            left join u.customer c
            where u.userId = :userId
            """)
    List<Object[]> findPrincipalRowsByUserId(@Param("userId") String userId);

    @Query("""
            select u.userId, u.email, u.passwordHash, u.status,
                   r.code, r.name, p.code,
                   c.id, c.name, c.phone, c.address
            from User u
            join u.role r
            left join r.permissions rp
            left join rp.permission p
            left join u.customer c
            where u.email = :email
            """)
    List<Object[]> findPrincipalRowsByEmail(@Param("email") String email);

    // Native queries bypass @SQLRestriction so deleted rows can answer with a
    // clear "account deleted" error instead of failing on a unique insert.
    @Query(value = "SELECT * FROM users WHERE lower(user_email) = lower(:email)", nativeQuery = true)
    Optional<User> findByEmailIncludingDeleted(@Param("email") String email);

    @Query(value = "SELECT * FROM users WHERE google_sub = :googleSub", nativeQuery = true)
    Optional<User> findByGoogleSubIncludingDeleted(@Param("googleSub") String googleSub);

    boolean existsByEmail(String email);

    List<User> findByStatusNot(UserStatus status);
}
