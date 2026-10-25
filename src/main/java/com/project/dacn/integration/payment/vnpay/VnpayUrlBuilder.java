package com.project.dacn.integration.payment.vnpay;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class VnpayUrlBuilder {

    private static final ZoneId VN_TIME = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VnpayProperties properties;
    private final VnpaySignatureService signatureService;

    public VnpayUrlBuilder(VnpayProperties properties, VnpaySignatureService signatureService) {
        this.properties = properties;
        this.signatureService = signatureService;
    }

    public String build(String txnRef, long amountVnd, String orderInfo, String ipAddress) {
        LocalDateTime now = LocalDateTime.now(VN_TIME);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", properties.getVersion());
        params.put("vnp_Command", properties.getCommand());
        params.put("vnp_TmnCode", properties.getTmnCode());
        params.put("vnp_Amount", String.valueOf(amountVnd * 100));
        params.put("vnp_CurrCode", properties.getCurrCode());
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", orderInfo);
        params.put("vnp_OrderType", properties.getOrderType());
        params.put("vnp_Locale", properties.getLocale());
        params.put("vnp_ReturnUrl", properties.getReturnUrl());
        params.put("vnp_IpAddr", ipAddress);
        params.put("vnp_CreateDate", now.format(DATE_FORMAT));
        params.put("vnp_ExpireDate", now.plusMinutes(15).format(DATE_FORMAT));

        String canonical = signatureService.canonical(params);
        return properties.getPayUrl()
                + "?" + canonical
                + "&vnp_SecureHash=" + signatureService.sign(canonical);
    }
}
