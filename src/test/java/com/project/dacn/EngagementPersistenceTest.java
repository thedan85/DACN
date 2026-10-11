package com.project.dacn;

import com.project.dacn.entity.Role;
import com.project.dacn.entity.User;
import com.project.dacn.repository.RoleRepository;
import com.project.dacn.repository.UserRepository;
import com.project.dacn.entity.Product;
import com.project.dacn.entity.Variant;
import com.project.dacn.repository.ProductRepository;
import com.project.dacn.repository.VariantRepository;
import com.project.dacn.entity.Notification;
import com.project.dacn.entity.Review;
import com.project.dacn.repository.NotificationRepository;
import com.project.dacn.repository.ReviewRepository;
import com.project.dacn.entity.Customer;
import com.project.dacn.repository.CustomerRepository;
import com.project.dacn.entity.Order;
import com.project.dacn.entity.OrderItem;
import com.project.dacn.repository.OrderItemRepository;
import com.project.dacn.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class EngagementPersistenceTest {

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
    OrderRepository orderRepository;
    @Autowired
    OrderItemRepository orderItemRepository;
    @Autowired
    ReviewRepository reviewRepository;
    @Autowired
    NotificationRepository notificationRepository;

    @Test
    void persistReviewAndNotification() {
        Role role = roleRepository.save(Role.builder().code("en1").name("R").build());
        User user = userRepository.save(User.builder().email("eng@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        Variant variant = variantRepository.save(Variant.builder().product(product)
                .sku("ENG-SKU-1").price(BigDecimal.ONE).stock(5).build());
        Order order = orderRepository.save(Order.builder()
                .customer(customer).total(BigDecimal.ONE)
                .address("A").recipientName("C").phone("090").build());
        OrderItem item = orderItemRepository.save(OrderItem.builder()
                .order(order).variant(variant).quantity(1).unitPrice(BigDecimal.ONE).build());

        Review review = reviewRepository.save(Review.builder()
                .variant(variant).customer(customer).orderItem(item)
                .rating(5).comment("great").build());
        assertThat(review.getReviewId()).isNotNull();

        Notification notification = notificationRepository.save(Notification.builder()
                .user(user).message("order shipped").build());
        assertThat(notification.getNotificationId()).isNotNull();
        assertThat(notificationRepository.findByUser_UserId(user.getUserId())).hasSize(1);
    }
}
