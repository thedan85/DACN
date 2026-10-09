package com.project.dacn.integration.payment.momo;

import org.springframework.stereotype.Service;

@Service
public class MomoSignatureService {

    private final MomoProperties properties;

    public MomoSignatureService(MomoProperties properties) {
        this.properties = properties;
    }

    public String sign(String rawData) {
        // TODO: HMAC-SHA256 with secretKey
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public boolean verify(String rawData, String signature) {
        // TODO: compare signatures
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
