package com.project.dacn.service.cart;

import com.project.dacn.dto.response.cart.CompatibilityResponse;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.ProductAttributeValue;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CompatibilityService {

    private static final String FISH_CATEGORY_ID = "fish";
    private static final int MAX_CATEGORY_DEPTH = 5;

    private final ProductAttributeValueRepository attributeRepository;

    public CompatibilityResponse evaluate(List<CartItem> items) {
        Map<String, Product> fish = new LinkedHashMap<>();
        for (CartItem item : items) {
            Product product = item.getVariant() == null ? null : item.getVariant().getProduct();
            if (product != null && isFish(product)) {
                fish.putIfAbsent(product.getProductId(), product);
            }
        }

        if (fish.size() < 2) {
            return CompatibilityCalculator.evaluate(List.of());
        }

        Map<String, Map<String, String>> traits = loadTraits(fish.keySet().stream().toList());
        List<CompatibilityCalculator.FishTraits> evaluated = fish.values().stream()
                .map(product -> toTraits(product, traits.getOrDefault(product.getProductId(), Map.of())))
                .toList();
        return CompatibilityCalculator.evaluate(evaluated);
    }

    private Map<String, Map<String, String>> loadTraits(List<String> productIds) {
        Map<String, Map<String, String>> traits = new HashMap<>();
        for (ProductAttributeValue value : attributeRepository.findByProduct_ProductIdIn(productIds)) {
            traits.computeIfAbsent(value.getProduct().getProductId(), key -> new HashMap<>())
                    .put(value.getAttribute().getAttributeId(), value.getAttributeValue().getValue());
        }
        return traits;
    }

    private CompatibilityCalculator.FishTraits toTraits(Product product, Map<String, String> values) {
        return new CompatibilityCalculator.FishTraits(
                product.getProductId(),
                product.getName(),
                TraitParser.parseRange(values.get("temperature")),
                TraitParser.parseRange(values.get("ph")),
                TraitParser.parseRange(values.get("size")),
                TraitParser.parseNumber(values.get("minimum-tank-size")),
                values.get("temperament"),
                values.get("diet"));
    }

    private boolean isFish(Product product) {
        Category category = product.getCategory();
        for (int depth = 0; depth < MAX_CATEGORY_DEPTH && category != null; depth++) {
            if (FISH_CATEGORY_ID.equals(category.getCategoryId())) {
                return true;
            }
            category = category.getParent();
        }
        return false;
    }
}
