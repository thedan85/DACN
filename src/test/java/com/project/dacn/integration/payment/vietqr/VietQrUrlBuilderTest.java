package com.project.dacn.integration.payment.vietqr;

import com.project.dacn.common.exception.VietQrNotConfiguredException;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VietQrUrlBuilderTest {

    private static VietQrUrlBuilder builder(String bankId, String accountNo, String accountName) {
        VietQrProperties properties = new VietQrProperties();
        properties.setBankId(bankId);
        properties.setAccountNo(accountNo);
        properties.setAccountName(accountName);
        return new VietQrUrlBuilder(properties);
    }

    @Test
    void buildsImageUrlWithAmountAndTransferContent() {
        String url = builder("VCB", "0123456789", "AQUA SHOP")
                .build("ORD_A1B2C3D4", 150000L);

        assertThat(url).startsWith(
                "https://img.vietqr.io/image/VCB-0123456789-compact2.png?");
        assertThat(url).contains("amount=150000");
        assertThat(url).contains("addInfo=ORD_A1B2C3D4");
        assertThat(url).contains("accountName=" + UriUtils.encodeQueryParam(
                "AQUA SHOP", StandardCharsets.UTF_8));
    }

    @Test
    void encodesTransferContentSpecialCharacters() {
        String url = builder("VCB", "0123456789", "SHOP")
                .build("ORD 1&2", 1000L);

        assertThat(url).contains("addInfo=" + UriUtils.encodeQueryParam(
                "ORD 1&2", StandardCharsets.UTF_8));
        assertThat(url).doesNotContain(" ");
        assertThat(url).doesNotContain("ORD 1&2");
    }

    @Test
    void rejectsMissingBankConfiguration() {
        assertThatThrownBy(() -> builder("", "0123456789", "SHOP")
                .build("ORD_1", 1000L))
                .isInstanceOf(VietQrNotConfiguredException.class)
                .hasMessageContaining("payment.vietqr.bank-id");
    }
}
