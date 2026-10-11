package com.project.dacn.modules.catalog.repository;

import com.project.dacn.modules.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByCategoryId(String categoryId);

    Optional<Category> findBySlug(String slug);

    List<Category> findByDeletedFalse();
}
