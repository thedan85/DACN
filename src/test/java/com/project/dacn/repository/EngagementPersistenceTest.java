package com.project.dacn.repository;

import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.entity.engagement.Notification;
import com.project.dacn.entity.engagement.Review;
import com.project.dacn.repository.engagement.NotificationRepository;
import com.project.dacn.repository.engagement.ReviewRepository;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    @Autowired
    PlatformTransactionManager transactionManager;

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

    @Test
    void findForAdminMatchesCommentCustomerProductAndRating() {
        Role role = roleRepository.save(Role.builder()
                .code("eng-review-admin").name("Review Admin").build());
        User user = userRepository.save(User.builder()
                .email("review-admin@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("An").phone("090").build());
        Product product = productRepository.save(Product.builder()
                .name("Cichlid").description("d").build());
        Variant variant = variantRepository.save(Variant.builder()
                .product(product).sku("REV-SKU-1").price(BigDecimal.ONE).stock(5).build());
        Review review = reviewRepository.save(Review.builder()
                .variant(variant).customer(customer)
                .rating(5).comment("algae bloom").build());

        Pageable page = PageRequest.of(0, 20);

        assertThat(reviewRepository.findForAdmin("algae", null, page).getTotalElements())
                .isEqualTo(1);
        assertThat(reviewRepository.findForAdmin("AN", null, page).getTotalElements())
                .isEqualTo(1);
        assertThat(reviewRepository.findForAdmin("CICHLID", null, page).getTotalElements())
                .isEqualTo(1);
        assertThat(reviewRepository.findForAdmin(null, 5, page).getTotalElements())
                .isEqualTo(1);
        assertThat(reviewRepository.findForAdmin(null, 4, page).getTotalElements())
                .isZero();
        assertThat(reviewRepository.findForAdmin("missing", null, page).getTotalElements())
                .isZero();
        assertThat(reviewRepository.findForAdmin(null, null, page).getContent())
                .extracting(Review::getReviewId)
                .containsExactly(review.getReviewId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoCommittedDeletesDeleteTheRowOnce() throws Exception {
        Review review = committedReview();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> winner = executor.submit(() -> transaction.execute(status -> {
                int rows = reviewRepository.deleteByReviewId(review.getReviewId());
                deleted.countDown();
                await(release);
                return rows;
            }));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> loser = executor.submit(() -> transaction.execute(
                    status -> reviewRepository.deleteByReviewId(review.getReviewId())));
            release.countDown();

            assertThat(winner.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(loser.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(reviewRepository.findByReviewId(review.getReviewId())).isEmpty();
    }

    private Review committedReview() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Role role = roleRepository.save(Role.builder()
                    .code("eng-review-race").name("Review Race").build());
            User user = userRepository.save(User.builder()
                    .email("review-race@x.com").passwordHash("h").role(role).build());
            Customer customer = customerRepository.save(Customer.builder()
                    .user(user).name("Race").phone("090").build());
            Product product = productRepository.save(Product.builder()
                    .name("Race").description("d").build());
            Variant variant = variantRepository.save(Variant.builder()
                    .product(product).sku("RACE-SKU-1").price(BigDecimal.ONE).stock(5).build());
            return reviewRepository.save(Review.builder()
                    .variant(variant).customer(customer)
                    .rating(4).comment("race").build());
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
