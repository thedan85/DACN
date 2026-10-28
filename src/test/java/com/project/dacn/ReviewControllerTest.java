package com.project.dacn;

import com.project.dacn.common.response.PageResponse;
import com.project.dacn.controller.engagement.ReviewController;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.security.CustomerIdentity;
import com.project.dacn.security.JwtAuthenticationFilter;
import com.project.dacn.service.engagement.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReviewController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReviewService service;

    @MockitoBean
    CustomerIdentity identity;

    @Test
    void listsReviewsForOwnedOrder() throws Exception {
        when(identity.requireCustomerId(any())).thenReturn(42L);
        when(service.listForOrder(eq(42L), eq("ORD_1"), any(Pageable.class)))
                .thenReturn(page());

        mockMvc.perform(get("/api/v1/orders/ORD_1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reviewId").value("REV_1"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(service).listForOrder(eq(42L), eq("ORD_1"), any(Pageable.class));
    }

    @Test
    void listsReviewsForAnyOrderAsStaff() throws Exception {
        when(service.listForOrderAsStaff(eq("ORD_1"), any(Pageable.class)))
                .thenReturn(page());

        mockMvc.perform(get("/api/v1/staff/orders/ORD_1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reviewId").value("REV_1"));
    }

    @Test
    void listsVariantReviewsPublicly() throws Exception {
        when(service.listForVariant(eq("VAR_1"), eq(false), any(Pageable.class)))
                .thenReturn(page());

        mockMvc.perform(get("/api/v1/variants/VAR_1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].variantId").value("VAR_1"));
    }

    @Test
    void createsReviewForPurchasedVariant() throws Exception {
        when(identity.requireCustomerId(any())).thenReturn(42L);
        when(service.create(eq(42L), eq("VAR_1"), any())).thenReturn(review());

        mockMvc.perform(post("/api/v1/variants/VAR_1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"great\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewId").value("REV_1"))
                .andExpect(jsonPath("$.data.verifiedPurchase").value(true));

        verify(service).create(eq(42L), eq("VAR_1"), any());
    }

    @Test
    void rejectsRatingOutsideOneToFiveOnCreate() throws Exception {
        mockMvc.perform(post("/api/v1/variants/VAR_1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":6,\"comment\":\"great\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void updatesOwnReview() throws Exception {
        when(identity.requireCustomerId(any())).thenReturn(42L);
        when(service.update(eq(42L), eq("REV_1"), any())).thenReturn(review());

        mockMvc.perform(patch("/api/v1/reviews/REV_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"comment\":\"updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewId").value("REV_1"));

        verify(service).update(eq(42L), eq("REV_1"), any());
    }

    @Test
    void deletesOwnReview() throws Exception {
        when(identity.requireCustomerId(any())).thenReturn(42L);

        mockMvc.perform(delete("/api/v1/reviews/REV_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(service).delete(42L, "REV_1");
    }

    private PageResponse<ReviewResponse> page() {
        return new PageResponse<>(List.of(review()), 0, 20, 1, 1);
    }

    private ReviewResponse review() {
        return new ReviewResponse("REV_1", "VAR_1", null, "An", true, 5,
                "great", null, null);
    }
}
