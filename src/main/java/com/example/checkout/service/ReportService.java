package com.example.checkout.service;

import com.example.checkout.domain.Coupon;
import com.example.checkout.domain.Order;
import com.example.checkout.domain.OrderLine;
import com.example.checkout.repo.CouponRepo;
import com.example.checkout.repo.OrderRepo;
import com.example.checkout.web.dto.Dtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ReportService {

    private final OrderRepo orders;
    private final CouponRepo coupons;

    public ReportService(OrderRepo orders, CouponRepo coupons) {
        this.orders = orders;
        this.coupons = coupons;
    }

    @Transactional(readOnly = true)
    public ReportView build() {
        List<Order> all = orders.findAll();
        long gross = 0, discount = 0, net = 0;
        Map<String, ProductSalesAgg> agg = new LinkedHashMap<>();
        for (Order o : all) {
            gross += o.getGrossCents();
            discount += o.getDiscountCents();
            net += o.getNetCents();
            for (OrderLine l : o.getLines()) {
                agg.computeIfAbsent(l.getProductId(),
                                k -> new ProductSalesAgg(l.getProductId(), l.getProductName()))
                        .add(l.getQuantity(), l.getLineTotalCents());
            }
        }
        List<ProductSalesRow> byProduct = agg.values().stream()
                .map(a -> new ProductSalesRow(a.id, a.name, a.units, a.grossCents))
                .toList();

        List<Coupon> allCoupons = coupons.findAllByOrderByMilestoneAsc();
        long available = allCoupons.stream().filter(c -> c.getStatus() == Coupon.Status.AVAILABLE).count();
        long redeemed = allCoupons.stream().filter(c -> c.getStatus() == Coupon.Status.REDEEMED).count();
        List<CouponReportView> couponViews = allCoupons.stream()
                .map(c -> new CouponReportView(c.getCode(), c.getPercent(), c.getMilestone(),
                        c.getStatus().name(), c.getCreatedAt(),
                        c.getRedeemedAt(), c.getRedeemedOnOrderId()))
                .toList();

        return new ReportView(all.size(), gross, discount, net, byProduct,
                allCoupons.size(), available, redeemed, couponViews);
    }

    private static final class ProductSalesAgg {
        final String id;
        final String name;
        long units;
        long grossCents;
        ProductSalesAgg(String id, String name) { this.id = id; this.name = name; }
        void add(int q, long lineTotal) { units += q; grossCents += lineTotal; }
    }
}
