package com.project.dacn;

import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.cart.CartItemRepository;
import com.project.dacn.repository.cart.CartRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CartRepositoryTest {

    @Autowired
    RoleRepository roleRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CustomerRepository customerRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    VariantRepository variantRepository;
    @Autowired
    CartRepository cartRepository;
    @Autowired
    CartItemRepository cartItemRepository;

    private Cart seedCartWithOneItem() {
        Role role = roleRepository.save(Role.builder().code("cr2").name("R").build());
        User user = userRepository.save(User.builder().email("repo@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        Variant variant = variantRepository.save(Variant.builder().product(product)
                .sku("REPO-SKU-1").price(new BigDecimal("10.00")).stock(5).build());
        Cart cart = cartRepository.save(Cart.builder().customer(customer).build());
        cartItemRepository.save(CartItem.builder().cart(cart).variant(variant).quantity(2).build());
        return cart;
    }

    @Test
    void upsertLookupFindsExistingRow() {
        Cart cart = seedCartWithOneItem();
        Variant variant = variantRepository.findBySku("REPO-SKU-1").orElseThrow();
        assertThat(cartItemRepository
                .findByCart_CartIdAndVariant_VariantId(cart.getCartId(), variant.getVariantId())).isPresent();
    }

    @Test
    void findWithDetailsFetchesVariantAndProduct() {
        Cart cart = seedCartWithOneItem();
        List<CartItem> items = cartItemRepository.findWithDetailsByCart_CartId(cart.getCartId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getVariant().getPrice()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(items.get(0).getVariant().getProduct().getName()).isEqualTo("P");
    }

    @Test
    void deleteByCartIdRemovesAllRows() {
        Cart cart = seedCartWithOneItem();
        cartItemRepository.deleteByCart_CartId(cart.getCartId());
        assertThat(cartItemRepository.findByCart_CartId(cart.getCartId())).isEmpty();
    }

    @Test
    void variantLookupFetchesProduct() {
        seedCartWithOneItem();
        Variant variant = variantRepository.findBySku("REPO-SKU-1").orElseThrow();
        assertThat(variantRepository.findWithProductByVariantId(variant.getVariantId()))
                .isPresent()
                .get().extracting(v -> v.getProduct().getName()).isEqualTo("P");
    }
}
