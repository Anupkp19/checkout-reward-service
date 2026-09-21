package com.example.checkout.web;

import com.example.checkout.service.CheckoutService;
import com.example.checkout.web.dto.Dtos.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/carts")
public class CheckoutController {

    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/{id}/checkout")
    public OrderView checkout(@PathVariable String id,
                              @RequestBody(required = false) CheckoutRequest body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return checkout.checkout(id, body == null ? new CheckoutRequest(null) : body, idemKey);
    }
}
