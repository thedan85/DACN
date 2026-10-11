package com.project.dacn.repository;

import com.project.dacn.entity.Media;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MediaRepository extends JpaRepository<Media, Long> {
    Optional<Media> findByMediaId(String mediaId);

    List<Media> findByProduct_ProductId(String productId);

    List<Media> findByVariant_VariantId(String variantId);
}
