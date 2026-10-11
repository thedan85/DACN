package com.project.dacn;

import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.repository.cart.CartItemRepository;
import com.project.dacn.repository.cart.CartRepository;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.identity.CustomerRepository;
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
class CartPersistenceTest {

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

    @Test
    void persistCartAndItemsThroughBusinessKeys() {
        Role role = roleRepository.save(Role.builder().code("cr1").name("R").build());
        User user = userRepository.save(User.builder().email("cart@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        Variant variant = variantRepository.save(Variant.builder().product(product)
                .sku("CART-SKU-1").price(BigDecimal.TEN).stock(5).build());

        Cart cart = cartRepository.save(Cart.builder().customer(customer).build());
        assertThat(cart.getCartId()).isNotNull();

        CartItem item = cartItemRepository.save(CartItem.builder()
                .cart(cart).variant(variant).quantity(2).build());
        assertThat(item.getCartItemId()).isNotNull();

        assertThatThrownBy(() -> cartItemRepository.saveAndFlush(CartItem.builder()
                .cart(cart).variant(variant).quantity(1).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
