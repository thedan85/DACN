package com.project.dacn;

import com.project.dacn.integration.shipping.ghn.GhnProperties;
import com.project.dacn.integration.shipping.ghn.GhnWebhookService;
import com.project.dacn.integration.shipping.ghn.dto.GhnWebhookPayload;
import com.project.dacn.service.order.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.project.dacn.integration.shipping.ghn.GhnStatusMapper.Event.DELIVERED;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GhnWebhookServiceTest {

    @Mock
    OrderService orderService;

    @Mock
    GhnProperties properties;

    @InjectMocks
    GhnWebhookService service;

    @Test
    void switchStatusDelegatesMappedEvent() {
        when(properties.getShopId()).thenReturn("237833");
        service.handle(new GhnWebhookPayload(237833, "GHN-1", "ORD_1", "switch_status", "delivered", "", ""));
        verify(orderService).applyGhnEvent("GHN-1", "ORD_1", DELIVERED);
    }

    @Test
    void otherTypeIsIgnored() {
        service.handle(new GhnWebhookPayload(237833, "GHN-1", "ORD_1", "create", "delivered", "", ""));
        verifyNoInteractions(orderService);
    }

    @Test
    void foreignShopIsIgnored() {
        when(properties.getShopId()).thenReturn("237833");
        service.handle(new GhnWebhookPayload(1, "GHN-1", "ORD_1", "switch_status", "delivered", "", ""));
        verifyNoInteractions(orderService);
    }
}
