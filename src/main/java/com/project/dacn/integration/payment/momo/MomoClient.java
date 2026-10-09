package com.project.dacn.integration.payment.momo;

import org.springframework.stereotype.Component;

@Component
public class MomoClient {

    private final MomoProperties properties;

    public MomoClient(MomoProperties properties) {
        this.properties = properties;
    }

    public MomoResponse createPayment(MomoRequest request) {
        // TODO: call MoMo endpoint
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
