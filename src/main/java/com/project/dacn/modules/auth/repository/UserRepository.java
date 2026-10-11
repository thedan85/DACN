package com.project.dacn.modules.auth.repository;

import com.project.dacn.modules.auth.entity.User;
import com.project.dacn.modules.common.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUserId(String userId);

    Optional<User> findByEmail(String email);

    List<User> findByStatusNot(UserStatus status);
}
