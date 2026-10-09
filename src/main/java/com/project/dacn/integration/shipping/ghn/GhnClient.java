package com.project.dacn.integration.shipping.ghn;

import com.project.dacn.integration.shipping.ShippingProvider;
import org.springframework.stereotype.Component;

@Component
public class GhnClient implements ShippingProvider {

    private final GhnProperties properties;

    public GhnClient(GhnProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "GHN";
    }
}
