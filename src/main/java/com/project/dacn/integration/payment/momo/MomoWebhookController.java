package com.project.dacn.integration.payment.momo;

import com.project.dacn.common.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/momo")
public class MomoWebhookController {

    @PostMapping
    public ApiResponse<String> handleWebhook(@RequestBody String payload) {
        // TODO: verify signature, update order status
        return ApiResponse.ok("received");
    }
}
