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
            predicates.add(cb.notEqual(root.get(Product_.status), ProductStatus.DELETED));
            if (filter.name() != null && !filter.name().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get(Product_.name)),
                        "%" + filter.name().toLowerCase() + "%"));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get(Product_.status), filter.status()));
            }
            if (filter.categoryId() != null) {
                var category = root.join(Product_.category, JoinType.LEFT);
                predicates.add(cb.equal(category.get(Category_.categoryId), filter.categoryId()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
