package com.project.dacn.modules.cart;

import com.project.dacn.modules.auth.entity.Role;
import com.project.dacn.modules.auth.entity.User;
import com.project.dacn.modules.auth.repository.RoleRepository;
import com.project.dacn.modules.auth.repository.UserRepository;
import com.project.dacn.modules.cart.entity.Cart;
import com.project.dacn.modules.cart.entity.CartItem;
import com.project.dacn.modules.cart.repository.CartItemRepository;
import com.project.dacn.modules.cart.repository.CartRepository;
import com.project.dacn.modules.catalog.entity.Product;
import com.project.dacn.modules.catalog.entity.Variant;
import com.project.dacn.modules.catalog.repository.ProductRepository;
import com.project.dacn.modules.catalog.repository.VariantRepository;
import com.project.dacn.modules.identity.entity.Customer;
import com.project.dacn.modules.identity.repository.CustomerRepository;
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
