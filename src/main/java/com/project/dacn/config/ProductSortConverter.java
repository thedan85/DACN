package com.project.dacn.config;

import com.project.dacn.common.constant.ProductSort;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Binds the public, snake_case sort values from the API contract
 * ({@code newest}, {@code price_asc}, {@code price_desc}) to {@link ProductSort}.
 */
@Component
public class ProductSortConverter implements Converter<String, ProductSort> {

    @Override
    public ProductSort convert(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String normalized = source.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return ProductSort.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "sort must be one of newest, price_asc, price_desc: " + source, ex);
        }
    }
}
