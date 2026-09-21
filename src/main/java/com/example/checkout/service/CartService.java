package com.example.checkout.service;

import com.example.checkout.domain.Cart;
import com.example.checkout.domain.CartItem;
import com.example.checkout.domain.Product;
import com.example.checkout.error.ApiException;
import com.example.checkout.repo.CartRepo;
import com.example.checkout.repo.ProductRepo;
import com.example.checkout.web.dto.Dtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CartService {

    private final CartRepo cartRepo;
    private final ProductRepo productRepo;

    public CartService(CartRepo cartRepo, ProductRepo productRepo) {
        this.cartRepo = cartRepo;
        this.productRepo = productRepo;
    }

    @Transactional
    public CartView create() {
        Cart cart = cartRepo.save(new Cart());
        return view(cart, Map.of());
    }

    @Transactional(readOnly = true)
    public CartView get(String cartId) {
        Cart cart = cartRepo.findById(cartId)
                .orElseThrow(() -> notFound(cartId));
        Map<String, Product> products = productSnapshot(cart);
        return view(cart, products);
    }

    @Transactional
    public CartView addItem(String cartId, AddItemRequest req) {
        Cart cart = requireOpen(cartId);
        Product product = productRepo.findById(req.productId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "unknown_product", "product not found", Map.of("productId", req.productId())));
        if (req.quantity() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_quantity",
                    "quantity must be >= 1");
        }

        Optional<CartItem> existing = cart.getItems().stream()
                .filter(i -> i.getProductId().equals(product.getId()))
                .findFirst();
        if (existing.isPresent()) {
            existing.get().setQuantity(Math.addExact(existing.get().getQuantity(), req.quantity()));
        } else {
            cart.getItems().add(new CartItem(cart, product.getId(), req.quantity()));
        }
        cartRepo.save(cart);
        return view(cart, productSnapshot(cart));
    }

    @Transactional
    public CartView updateItem(String cartId, String productId, UpdateItemRequest req) {
        Cart cart = requireOpen(cartId);
        CartItem item = cart.getItems().stream()
                .filter(i -> i.getProductId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "item_not_in_cart", "cart does not contain that product"));
        if (req.quantity() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_quantity",
                    "quantity must be >= 1");
        }
        item.setQuantity(req.quantity());
        cartRepo.save(cart);
        return view(cart, productSnapshot(cart));
    }

    @Transactional
    public CartView removeItem(String cartId, String productId) {
        Cart cart = requireOpen(cartId);
        boolean removed = cart.getItems().removeIf(i -> i.getProductId().equals(productId));
        if (!removed) {
            throw new ApiException(HttpStatus.NOT_FOUND, "item_not_in_cart",
                    "cart does not contain that product");
        }
        cartRepo.save(cart);
        return view(cart, productSnapshot(cart));
    }

    private Cart requireOpen(String cartId) {
        Cart cart = cartRepo.findById(cartId).orElseThrow(() -> notFound(cartId));
        if (cart.getStatus() != Cart.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "cart_not_open",
                    "cart is not open for modification", Map.of("status", cart.getStatus().name()));
        }
        return cart;
    }

    private Map<String, Product> productSnapshot(Cart cart) {
        List<String> ids = cart.getItems().stream().map(CartItem::getProductId).toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, Product> byId = new HashMap<>();
        for (Product p : productRepo.findAllById(ids)) byId.put(p.getId(), p);
        return byId;
    }

    private CartView view(Cart cart, Map<String, Product> products) {
        List<CartLineView> lines = new ArrayList<>();
        long subtotal = 0;
        for (CartItem it : cart.getItems()) {
            Product p = products.get(it.getProductId());
            long unit = p != null ? p.getUnitPriceCents() : 0L;
            String name = p != null ? p.getName() : "(deleted product)";
            Integer avail = p != null ? p.getInventory() : null;
            long lineTotal = Math.multiplyExact(unit, (long) it.getQuantity());
            subtotal = Math.addExact(subtotal, lineTotal);
            lines.add(new CartLineView(it.getProductId(), name, unit, it.getQuantity(),
                    lineTotal, avail, false));
        }
        return new CartView(cart.getId(), cart.getStatus().name(), lines, subtotal);
    }

    private ApiException notFound(String cartId) {
        return new ApiException(HttpStatus.NOT_FOUND, "cart_not_found",
                "cart not found", Map.of("cartId", cartId));
    }
}
