package com.example.checkout.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record ProductView(String id, String name, long unitPriceCents, int inventory) {}

    public record AddItemRequest(
            @NotBlank String productId,
            @Min(value = 1, message = "quantity must be >= 1") int quantity) {}

    public record UpdateItemRequest(
            @Min(value = 1, message = "quantity must be >= 1") int quantity) {}

    public record CartLineView(String productId, String name, long unitPriceCents,
                               int quantity, long lineTotalCents,
                               Integer availableInventory,
                               boolean priceChangedSinceAdd) {}

    public record CartView(String cartId, String status, List<CartLineView> lines,
                           long subtotalCents) {}

    public record CheckoutRequest(String couponCode) {}

    public record OrderLineView(String productId, String productName,
                                long unitPriceCents, int quantity, long lineTotalCents) {}

    public record OrderView(String orderId, String cartId, Instant placedAt,
                            List<OrderLineView> lines,
                            long grossCents, long discountCents, long netCents,
                            String couponCode, Integer couponPercent) {}

    public record GenerateCouponResponse(String code, int percent, long milestone,
                                         String status, Instant createdAt) {}

    public record CouponReportView(String code, int percent, long milestone,
                                   String status, Instant createdAt,
                                   Instant redeemedAt, String redeemedOnOrderId) {}

    public record ProductSalesRow(String productId, String name, long unitsSold, long grossCents) {}

    public record ReportView(
            long totalOrders,
            long grossRevenueCents,
            long totalDiscountsCents,
            long netRevenueCents,
            List<ProductSalesRow> salesByProduct,
            long couponsGenerated,
            long couponsAvailable,
            long couponsRedeemed,
            List<CouponReportView> coupons) {}
}
