package com.project.dacn.spec;

import com.project.dacn.common.constant.ProductSort;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.request.catalog.ProductFilter;
import com.project.dacn.entity.BaseEntity_;
import com.project.dacn.entity.catalog.Category_;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Product_;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.catalog.Variant_;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class ProductSpecs {

    private ProductSpecs() {
    }

    /**
     * @param categoryIds expanded set of category ids: the requested category
     *                    plus all of its descendants. A parent filter must match
     *                    products filed under any child category.
     */
    public static Specification<Product> filter(ProductFilter filter, Collection<String> categoryIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Storefront reads only expose sellable products: when no explicit
            // status is requested, default to ACTIVE rather than "anything not
            // DELETED". An explicit status (DRAFT/INACTIVE/DELETED for admin
            // audit) still filters by it alone. Product also carries
            // @SQLRestriction("product_status <> 'DELETED'").
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get(Product_.status), filter.status()));
            } else {
                predicates.add(cb.equal(root.get(Product_.status), ProductStatus.ACTIVE));
            }
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get(Product_.name)),
                        "%" + filter.keyword().toLowerCase(Locale.ROOT) + "%"));
            }
            if (categoryIds != null && !categoryIds.isEmpty()) {
                predicates.add(root.get(Product_.category)
                        .get(Category_.categoryId).in(categoryIds));
            }
            // Both bounds compare against the lowest active variant price, so a
            // product is in range if any of its sellable variants is in range.
            if (filter.minPrice() != null || filter.maxPrice() != null) {
                Subquery<BigDecimal> minPrice = minPrice(query, root, cb);
                if (filter.minPrice() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(minPrice, filter.minPrice()));
                }
                if (filter.maxPrice() != null) {
                    predicates.add(cb.lessThanOrEqualTo(minPrice, filter.maxPrice()));
                }
            }
            applySort(query, root, cb, filter.sort());
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static void applySort(CriteriaQuery<?> query, Root<Product> root,
            CriteriaBuilder cb, ProductSort sort) {
        // The same Specification builds the count query; ordering there is
        // pointless and some providers reject it.
        if (Long.class.equals(query.getResultType())) {
            return;
        }
        ProductSort effective = sort == null ? ProductSort.NEWEST : sort;
        // id is unique, so it breaks ties the same way for every page window.
        switch (effective) {
            case NEWEST -> query.orderBy(
                    cb.desc(root.get(BaseEntity_.createdAt)),
                    cb.desc(root.get(BaseEntity_.id)));
            case PRICE_ASC -> {
                Subquery<BigDecimal> minPrice = minPrice(query, root, cb);
                // Portable NULLS LAST: products without a price sort after
                // priced ones, then by the lowest active variant price.
                query.orderBy(
                        cb.asc(cb.selectCase().when(cb.isNull(minPrice), 1).otherwise(0)),
                        cb.asc(minPrice),
                        cb.desc(root.get(BaseEntity_.id)));
            }
            case PRICE_DESC -> {
                Subquery<BigDecimal> maxPrice = maxPrice(query, root, cb);
                query.orderBy(
                        cb.asc(cb.selectCase().when(cb.isNull(maxPrice), 1).otherwise(0)),
                        cb.desc(maxPrice),
                        cb.desc(root.get(BaseEntity_.id)));
            }
        }
    }

    private static Subquery<BigDecimal> minPrice(CriteriaQuery<?> query, Root<Product> root,
            CriteriaBuilder cb) {
        Subquery<BigDecimal> subquery = query.subquery(BigDecimal.class);
        Root<Variant> variant = subquery.from(Variant.class);
        subquery.select(cb.min(variant.get(Variant_.price)))
                .where(cb.equal(variant.get(Variant_.product), root),
                        cb.isFalse(variant.get(Variant_.deleted)));
        return subquery;
    }

    private static Subquery<BigDecimal> maxPrice(CriteriaQuery<?> query, Root<Product> root,
            CriteriaBuilder cb) {
        Subquery<BigDecimal> subquery = query.subquery(BigDecimal.class);
        Root<Variant> variant = subquery.from(Variant.class);
        subquery.select(cb.max(variant.get(Variant_.price)))
                .where(cb.equal(variant.get(Variant_.product), root),
                        cb.isFalse(variant.get(Variant_.deleted)));
        return subquery;
    }
}
