package com.example.checkout.web;

import com.example.checkout.service.CartService;
import com.example.checkout.web.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/carts")
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    @PostMapping
    public ResponseEntity<CartView> create() {
        return ResponseEntity.status(HttpStatus.CREATED).body(carts.create());
    }

    @GetMapping("/{id}")
    public CartView get(@PathVariable String id) {
        return carts.get(id);
    }

    @PostMapping("/{id}/items")
    public CartView addItem(@PathVariable String id, @Valid @RequestBody AddItemRequest req) {
        return carts.addItem(id, req);
    }

    @PutMapping("/{id}/items/{productId}")
    public CartView updateItem(@PathVariable String id, @PathVariable String productId,
                               @Valid @RequestBody UpdateItemRequest req) {
        return carts.updateItem(id, productId, req);
    }

    @DeleteMapping("/{id}/items/{productId}")
    public CartView removeItem(@PathVariable String id, @PathVariable String productId) {
        return carts.removeItem(id, productId);
    }
}
