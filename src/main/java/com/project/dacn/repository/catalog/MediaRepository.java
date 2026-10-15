package com.project.dacn.repository.catalog;

import com.project.dacn.entity.catalog.Media;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MediaRepository extends JpaRepository<Media, Long> {
    Optional<Media> findByMediaId(String mediaId);

    List<Media> findByProduct_ProductId(String productId);

    List<Media> findByVariant_VariantId(String variantId);

    Page<Media> findByProduct_ProductId(String productId, Pageable pageable);

    Page<Media> findByVariant_VariantId(String variantId, Pageable pageable);

    Page<Media> findByDeletedFalse(Pageable pageable);
}
