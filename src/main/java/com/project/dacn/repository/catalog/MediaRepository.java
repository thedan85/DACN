package com.project.dacn.repository.catalog;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.entity.catalog.Media;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MediaRepository extends JpaRepository<Media, Long> {
    Optional<Media> findByMediaId(String mediaId);

    List<Media> findByProduct_ProductId(String productId);

    List<Media> findByVariant_VariantId(String variantId);

    List<Media> findByVariant_Product_ProductId(String productId);

    Page<Media> findByProduct_ProductId(String productId, Pageable pageable);

    Page<Media> findByVariant_Product_ProductId(String productId, Pageable pageable);

    Page<Media> findByVariant_VariantId(String variantId, Pageable pageable);

    Page<Media> findByDeletedFalse(Pageable pageable);

    /**
     * Media attached to an active product, directly or through its variant.
     * Used for anonymous reads so drafts do not leak.
     */
    @Query("""
            select m from Media m
            left join m.product p
            left join m.variant v
            left join v.product vp
            where m.deleted = false
              and (p.status = :status or vp.status = :status)
            """)
    Page<Media> findVisible(@Param("status") ProductStatus status,
            Pageable pageable);
}
