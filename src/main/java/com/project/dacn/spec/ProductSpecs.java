package com.project.dacn.spec;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.entity.catalog.Category_;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Product_;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class ProductSpecs {

    private ProductSpecs() {
    }

    public static Specification<Product> filter(ProductFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Only exclude DELETED by default. When an explicit status is requested
            // (including DELETED for admin recovery/audit), filter by it alone;
            // otherwise WHERE status != 'DELETED' AND status = 'DELETED' is always empty.
            // NOTE: Product also carries @SQLRestriction("product_status <> 'DELETED'"),
            // so true DELETED recovery still needs a native query bypassing that
            // restriction; this fix removes the contradictory Specification clause.
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get(Product_.status), filter.status()));
            } else {
                predicates.add(cb.notEqual(root.get(Product_.status), ProductStatus.DELETED));
            }
            if (filter.name() != null && !filter.name().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get(Product_.name)),
                        "%" + filter.name().toLowerCase() + "%"));
            }
            if (filter.categoryId() != null) {
                var category = root.join(Product_.category, JoinType.LEFT);
                predicates.add(cb.equal(category.get(Category_.categoryId), filter.categoryId()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
