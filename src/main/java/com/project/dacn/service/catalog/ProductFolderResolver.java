package com.project.dacn.service.catalog;

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
                ? slugify(product.getProductId())
                : slugify(product.getName());
        segments.add(productSegment);
        return "home/" + String.join("/", segments);
    }

    public static String slugify(String input) {
        if (input == null) {
            return "unnamed";
        }
        String slug = input.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isEmpty() ? "unnamed" : slug;
    }
}
