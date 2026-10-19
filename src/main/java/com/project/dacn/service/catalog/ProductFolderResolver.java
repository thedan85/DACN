package com.project.dacn.service.catalog;

import com.project.dacn.common.util.BusinessId;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Product;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ProductFolderResolver {

    public String resolve(Product product) {
        List<String> segments = new ArrayList<>();
        Category current = product.getCategory();
        while (current != null) {
            segments.add(slugify(current.getSlug() != null ? current.getSlug() : current.getName()));
            current = current.getParent();
        }
        Collections.reverse(segments);
        if (segments.size() > 1) {
            segments.remove(0);
        }
        String productSegment = product.getProductId() != null
                ? slugify(stripPrefix(product.getProductId()))
                : slugify(product.getName());
        segments.add(productSegment);
        return "home/" + String.join("/", segments);
    }

    /** Business IDs carry a type prefix ({@code PRD_x}); folders should not. */
    private static String stripPrefix(String businessId) {
        int underscore = businessId.indexOf('_');
        return underscore > 0 ? businessId.substring(underscore + 1) : businessId;
    }

    public static String slugify(String input) {
        return BusinessId.slug(input);
    }
}
