package com.project.dacn.integration.payment.vietqr;

import com.project.dacn.common.exception.VietQrNotConfiguredException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

@Component
public class VietQrUrlBuilder {

    private final VietQrProperties properties;

    public VietQrUrlBuilder(VietQrProperties properties) {
        this.properties = properties;
    }

    public String build(String addInfo, long amountVnd) {
        String bankId = require("payment.vietqr.bank-id", properties.getBankId());
        String accountNo = require("payment.vietqr.account-no", properties.getAccountNo());
        String accountName = require("payment.vietqr.account-name", properties.getAccountName());
        return properties.getImageBaseUrl()
                + "/" + bankId + "-" + accountNo + "-" + properties.getTemplate() + ".png"
                + "?amount=" + amountVnd
                + "&addInfo=" + UriUtils.encodeQueryParam(addInfo, StandardCharsets.UTF_8)
                + "&accountName=" + UriUtils.encodeQueryParam(accountName, StandardCharsets.UTF_8);
    }

    private static String require(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new VietQrNotConfiguredException(property);
        }
        return value.trim();
    }
}
