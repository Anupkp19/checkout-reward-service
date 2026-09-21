package com.example.checkout.web;

import com.example.checkout.domain.Coupon;
import com.example.checkout.service.CouponService;
import com.example.checkout.service.ReportService;
import com.example.checkout.web.dto.Dtos.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final CouponService couponService;
    private final ReportService reportService;

    public AdminController(CouponService couponService, ReportService reportService) {
        this.couponService = couponService;
        this.reportService = reportService;
    }

    @PostMapping("/coupons/generate")
    public GenerateCouponResponse generate() {
        Coupon c = couponService.generateNextEligible();
        return new GenerateCouponResponse(c.getCode(), c.getPercent(), c.getMilestone(),
                c.getStatus().name(), c.getCreatedAt());
    }

    @GetMapping("/report")
    public ReportView report() {
        return reportService.build();
    }
}
