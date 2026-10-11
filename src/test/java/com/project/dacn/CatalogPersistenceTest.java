package com.project.dacn;

import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.ProductAttributeValue;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.catalog.VariantAttributeValue;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class CatalogPersistenceTest {

    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    AttributeRepository attributeRepository;
    @Autowired
    AttributeValueRepository attributeValueRepository;
    @Autowired
    VariantRepository variantRepository;
    @Autowired
    MediaRepository mediaRepository;
    @Autowired
    VariantAttributeValueRepository variantAttributeValueRepository;
    @Autowired
    ProductAttributeValueRepository productAttributeValueRepository;

    @Test
    void persistCatalogChainThroughBusinessKeys() {
        Category parent = categoryRepository.save(Category.builder().name("Fish").slug("fish").build());
        Category child = categoryRepository.save(Category.builder()
                .name("Betta").slug("betta").parent(parent).build());
        assertThat(child.getCategoryId()).isNotNull();

        Product product = productRepository.save(Product.builder()
                .name("Betta Halfmoon").description("desc").category(child).build());

        Attribute color = attributeRepository.save(Attribute.builder().name("Color").build());
        AttributeValue red = attributeValueRepository.save(AttributeValue.builder()
                .attribute(color).value("Red").build());
        assertThat(red.getAttributeValueId()).isNotNull();

        Variant variant = variantRepository.save(Variant.builder()
                .product(product).sku("BETTA-RED-01")
                .price(new BigDecimal("12.50")).stock(10).build());

        VariantAttributeValue vav = variantAttributeValueRepository.save(VariantAttributeValue.builder()
                .variant(variant).attribute(color).attributeValue(red).build());
        assertThat(vav.getVariantValueId()).isNotNull();

        ProductAttributeValue pav = productAttributeValueRepository.save(ProductAttributeValue.builder()
                .product(product).attribute(color).attributeValue(red).build());
        assertThat(pav.getProductValueId()).isNotNull();

        Media media = mediaRepository.save(Media.builder()
                .url("http://img/1.jpg").variant(variant).build());
        assertThat(media.getMediaId()).isNotNull();

        assertThat(variantAttributeValueRepository.findByVariant_VariantId(variant.getVariantId())).hasSize(1);
    }

    @Test
    void duplicateSkuAndSlugRejected() {
        Category c = categoryRepository.save(Category.builder().name("N1").slug("slug-1").build());
        assertThatThrownBy(() -> categoryRepository.saveAndFlush(
                Category.builder().name("N2").slug("slug-1").build()))
                .isInstanceOf(DataIntegrityViolationException.class);

        Product p = productRepository.save(Product.builder()
                .name("P").description("d").category(c).build());
        variantRepository.save(Variant.builder().product(p).sku("SKU-1")
                .price(BigDecimal.ONE).stock(1).build());
        assertThatThrownBy(() -> variantRepository.saveAndFlush(Variant.builder()
                .product(p).sku("SKU-1").price(BigDecimal.ONE).stock(1).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void softDeletedProductHiddenByDefault() {
        Product p = productRepository.save(Product.builder().name("PX").description("d").build());
        p.setStatus(com.project.dacn.common.constant.ProductStatus.DELETED);
        productRepository.save(p);
        assertThat(productRepository.findByProductId(p.getProductId())).isEmpty();
    }
}
