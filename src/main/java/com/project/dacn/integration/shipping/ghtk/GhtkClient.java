package com.project.dacn.integration.shipping.ghtk;

import com.project.dacn.integration.shipping.ShippingProvider;
import org.springframework.stereotype.Component;

@Component
public class GhtkClient implements ShippingProvider {

    private final GhtkProperties properties;

    public GhtkClient(GhtkProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "GHTK";
    }
}
