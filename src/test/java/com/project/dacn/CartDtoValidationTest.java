package com.project.dacn;

import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.exception.OutOfStockException;
import com.project.dacn.dto.request.cart.CartItemAddRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CartDtoValidationTest {

    @Test
    void addRequestRejectsZeroQty() {
        var v = Validation.buildDefaultValidatorFactory().getValidator().validate(new CartItemAddRequest("v-1", 0));
        assertThat(v).isNotEmpty();
    }

    @Test
    void outOfStockMapsTo409() {
        var body = new GlobalExceptionHandler().handleOutOfStock(new OutOfStockException("x")).getBody();
        assertThat(body.getCode()).isEqualTo(409);
    }
}
