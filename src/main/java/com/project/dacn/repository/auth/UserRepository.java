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
    Optional<User> findByUserId(String userId);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByGoogleSub(String googleSub);

    // Native queries bypass @SQLRestriction so deleted rows can answer with a
    // clear "account deleted" error instead of failing on a unique insert.
    @Query(value = "SELECT * FROM users WHERE lower(user_email) = lower(:email)", nativeQuery = true)
    Optional<User> findByEmailIncludingDeleted(@Param("email") String email);

    @Query(value = "SELECT * FROM users WHERE google_sub = :googleSub", nativeQuery = true)
    Optional<User> findByGoogleSubIncludingDeleted(@Param("googleSub") String googleSub);

    boolean existsByEmail(String email);

    List<User> findByStatusNot(UserStatus status);
}
