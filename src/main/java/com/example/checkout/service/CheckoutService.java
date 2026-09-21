package com.example.checkout.service;

import com.example.checkout.domain.*;
import com.example.checkout.domain.Order;
import com.example.checkout.error.ApiException;
import com.example.checkout.money.Money;
import com.example.checkout.repo.*;
import com.example.checkout.web.dto.Dtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class CheckoutService {

    private final CartRepo carts;
    private final ProductRepo products;
    private final OrderRepo orders;
    private final CouponRepo coupons;
    private final IdempotencyService idempotency;

    private static final String ENDPOINT = "POST /carts/{id}/checkout";

    public CheckoutService(CartRepo carts, ProductRepo products, OrderRepo orders,
                           CouponRepo coupons, IdempotencyService idempotency) {
        this.carts = carts;
        this.products = products;
        this.orders = orders;
        this.coupons = coupons;
        this.idempotency = idempotency;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView checkout(String cartId, CheckoutRequest req, String idemKey) {
        String reqHash = idempotency.hashRequest(new IdemPayload(cartId, req));
        var prior = idempotency.lookup(ENDPOINT, idemKey);
        if (prior.isPresent()) {
            idempotency.ensureMatchingHash(prior.get(), reqHash);
            return idempotency.decode(prior.get(), OrderView.class);
        }

        Cart cart = carts.lockById(cartId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "cart_not_found",
                        "cart not found", Map.of("cartId", cartId)));

        Optional<Order> already = orders.findByCartId(cartId);
        if (already.isPresent()) {
            OrderView view = toView(already.get());
            idempotency.store(ENDPOINT, idemKey, reqHash, 200, view, view.orderId());
            return view;
        }

        if (cart.getStatus() != Cart.Status.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "cart_not_open",
                    "cart cannot be checked out", Map.of("status", cart.getStatus().name()));
        }
        if (cart.getItems().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_cart",
                    "cart is empty");
        }

        List<String> productIds = cart.getItems().stream()
                .map(CartItem::getProductId).sorted().toList();
        Map<String, Product> byId = new HashMap<>();
        for (Product p : products.lockAllByIds(productIds)) byId.put(p.getId(), p);
        if (byId.size() != productIds.size()) {
            List<String> missing = productIds.stream().filter(id -> !byId.containsKey(id)).toList();
            throw new ApiException(HttpStatus.CONFLICT, "product_missing",
                    "cart references products that no longer exist",
                    Map.of("productIds", missing));
        }

        List<Map<String, Object>> shortfalls = new ArrayList<>();
        for (CartItem it : cart.getItems()) {
            Product p = byId.get(it.getProductId());
            if (p.getInventory() < it.getQuantity()) {
                shortfalls.add(Map.of(
                        "productId", p.getId(),
                        "requested", it.getQuantity(),
                        "available", p.getInventory()));
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "insufficient_inventory",
                    "one or more products lack sufficient inventory",
                    Map.of("shortfalls", shortfalls));
        }

        Coupon coupon = null;
        if (req != null && req.couponCode() != null && !req.couponCode().isBlank()) {
            coupon = coupons.lockByCode(req.couponCode().trim())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                            "invalid_coupon", "coupon does not exist",
                            Map.of("code", req.couponCode())));
            if (coupon.getStatus() != Coupon.Status.AVAILABLE) {
                throw new ApiException(HttpStatus.CONFLICT, "coupon_not_available",
                        "coupon is not available for redemption",
                        Map.of("code", coupon.getCode(), "status", coupon.getStatus().name()));
            }
        }

        Order order = new Order();
        order.setCartId(cart.getId());
        long gross = 0;
        for (CartItem it : cart.getItems()) {
            Product p = byId.get(it.getProductId());
            OrderLine line = new OrderLine(order, p.getId(), p.getName(),
                    p.getUnitPriceCents(), it.getQuantity());
            order.getLines().add(line);
            gross = Math.addExact(gross, line.getLineTotalCents());
        }
        long discount = coupon == null ? 0L
                : Math.min(gross, Money.percentDiscountCents(gross, coupon.getPercent()));
        long net = Math.max(0L, gross - discount);

        order.setGrossCents(gross);
        order.setDiscountCents(discount);
        order.setNetCents(net);
        if (coupon != null) {
            order.setCouponCode(coupon.getCode());
            order.setCouponPercent(coupon.getPercent());
        }

        for (CartItem it : cart.getItems()) {
            Product p = byId.get(it.getProductId());
            p.setInventory(p.getInventory() - it.getQuantity());
        }

        if (coupon != null) {
            coupon.setStatus(Coupon.Status.REDEEMED);
            coupon.setRedeemedAt(Instant.now());
            coupon.setRedeemedOnOrderId(order.getId());
        }

        cart.setStatus(Cart.Status.CHECKED_OUT);
        Order saved = orders.saveAndFlush(order);

        OrderView view = toView(saved);
        idempotency.store(ENDPOINT, idemKey, reqHash, 200, view, saved.getId());
        return view;
    }

    public OrderView toView(Order o) {
        List<OrderLineView> lines = o.getLines().stream()
                .map(l -> new OrderLineView(l.getProductId(), l.getProductName(),
                        l.getUnitPriceCents(), l.getQuantity(), l.getLineTotalCents()))
                .toList();
        return new OrderView(o.getId(), o.getCartId(), o.getPlacedAt(), lines,
                o.getGrossCents(), o.getDiscountCents(), o.getNetCents(),
                o.getCouponCode(), o.getCouponPercent());
    }

    private record IdemPayload(String cartId, CheckoutRequest req) {}
}
