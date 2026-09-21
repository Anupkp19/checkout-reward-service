package com.example.checkout.service;

import com.example.checkout.config.RewardsConfig;
import com.example.checkout.domain.Coupon;
import com.example.checkout.error.ApiException;
import com.example.checkout.repo.CouponRepo;
import com.example.checkout.repo.OrderRepo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Map;

@Service
public class CouponService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final CouponRepo coupons;
    private final OrderRepo orders;
    private final RewardsConfig config;

    public CouponService(CouponRepo coupons, OrderRepo orders, RewardsConfig config) {
        this.coupons = coupons;
        this.orders = orders;
        this.config = config;
    }

    @Transactional
    public Coupon generateNextEligible() {
        long orderCount = orders.count();
        long eligibleMilestones = orderCount / config.getN();
        long generated = coupons.count();
        if (eligibleMilestones <= generated) {
            throw new ApiException(HttpStatus.CONFLICT, "no_milestone_available",
                    "no unrewarded milestone is eligible",
                    Map.of(
                            "orderCount", orderCount,
                            "n", config.getN(),
                            "eligibleMilestones", eligibleMilestones,
                            "couponsAlreadyGenerated", generated));
        }
        long milestone = Math.multiplyExact(generated + 1, (long) config.getN());
        Coupon c = new Coupon(newCode(), config.getXPercent(), milestone);
        try {
            return coupons.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            long recount = coupons.count();
            if (eligibleMilestones <= recount) {
                throw new ApiException(HttpStatus.CONFLICT, "no_milestone_available",
                        "milestone was just consumed by a concurrent request");
            }
            long retryMilestone = Math.multiplyExact(recount + 1, (long) config.getN());
            return coupons.saveAndFlush(new Coupon(newCode(), config.getXPercent(), retryMilestone));
        }
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder("SAVE-");
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
