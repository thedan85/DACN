package com.project.dacn;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.controller.engagement.AdminReviewController;
import com.project.dacn.dto.response.engagement.AdminReviewResponse;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.service.engagement.ReviewService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminReviewControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReviewService service;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    CustomUserDetailsService customUserDetailsService;

    @Test
    void listsReviewsWithFilters() throws Exception {
        when(service.listForAdmin(eq("cichlid"), eq(5), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(row()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/admin/reviews")
                        .param("keyword", "cichlid")
                        .param("rating", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reviewId").value("REV_1"))
                .andExpect(jsonPath("$.data.content[0].productName").value("Cichlid"))
                .andExpect(jsonPath("$.data.content[0].verifiedPurchase").value(true));
    }

    @Test
    void deletesReviewForTheAuthenticatedActor() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/reviews/REV_1")
                        .principal(() -> "USR_ADMIN"))
                .andExpect(status().isOk());

        verify(service).deleteAsAdmin("REV_1", "USR_ADMIN");
    }

    @Test
    void rejectsRatingOutsideOneToFive() throws Exception {
        when(service.listForAdmin(any(), eq(0), any(Pageable.class)))
                .thenThrow(new IllegalArgumentException("rating must be between 1 and 5"));

        mockMvc.perform(get("/api/v1/admin/reviews").param("rating", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonNumericRating() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reviews").param("rating", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnsupportedSortField() throws Exception {
        when(service.listForAdmin(any(), any(), any(Pageable.class)))
                .thenThrow(new IllegalArgumentException("unsupported sort field: comment"));

        mockMvc.perform(get("/api/v1/admin/reviews").param("sort", "comment,asc"))
                .andExpect(status().isBadRequest());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).listForAdmin(any(), any(), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("comment").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void returnsNotFoundForUnknownReview() throws Exception {
        doThrow(new ResourceNotFoundException("review not found: REV_404"))
                .when(service).deleteAsAdmin("REV_404", "USR_ADMIN");

        mockMvc.perform(delete("/api/v1/admin/reviews/REV_404")
                        .principal(() -> "USR_ADMIN"))
                .andExpect(status().isNotFound());
    }

    private AdminReviewResponse row() {
        return new AdminReviewResponse("REV_1", "CUS_1", "An", 5, "great",
                "PRD_1", "Cichlid", "VAR_1", "SKU-1", true,
                Instant.parse("2026-11-20T10:00:00Z"),
                Instant.parse("2026-11-20T10:00:00Z"));
    }
}
