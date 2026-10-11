package com.project.dacn.repository.auth;

import com.project.dacn.entity.auth.User;
import com.project.dacn.common.constant.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUserId(String userId);

    Optional<User> findByEmail(String email);

    List<User> findByStatusNot(UserStatus status);
}
