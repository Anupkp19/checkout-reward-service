package com.example.checkout.web;

import com.example.checkout.error.ApiException;
import com.example.checkout.repo.OrderRepo;
import com.example.checkout.service.CheckoutService;
import com.example.checkout.web.dto.Dtos.OrderView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepo orders;
    private final CheckoutService checkout;

    public OrderController(OrderRepo orders, CheckoutService checkout) {
        this.orders = orders;
        this.checkout = checkout;
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable String id) {
        return orders.findById(id).map(checkout::toView)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "order_not_found",
                        "order not found", Map.of("orderId", id)));
    }
}
