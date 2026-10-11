package com.project.dacn.modules.catalog.repository;

import com.project.dacn.modules.catalog.entity.Media;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MediaRepository extends JpaRepository<Media, Long> {
    Optional<Media> findByMediaId(String mediaId);

    List<Media> findByProduct_ProductId(String productId);

    List<Media> findByVariant_VariantId(String variantId);
}
