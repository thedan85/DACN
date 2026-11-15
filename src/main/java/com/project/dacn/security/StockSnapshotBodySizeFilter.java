package com.project.dacn.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class StockSnapshotBodySizeFilter extends BoundedBodySizeFilter {

    public StockSnapshotBodySizeFilter(ObjectMapper mapper) {
        super(mapper, 65536, "/api/v1/stock/snapshots");
    }
}
