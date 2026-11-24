package com.project.dacn.dto.response.payment;

import java.math.BigDecimal;

public record PaymentVietQrResponse(
        String qrUrl,
        String bankId,
        String accountNo,
        String accountName,
        BigDecimal amount,
        String addInfo) {
}
