package com.example.checkout.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}

    public static long percentDiscountCents(long grossCents, int percent) {
        if (percent <= 0) return 0L;
        if (percent >= 100) return grossCents;
        BigDecimal gross = BigDecimal.valueOf(grossCents);
        BigDecimal pct = BigDecimal.valueOf(percent).movePointLeft(2);
        return gross.multiply(pct).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static String formatCents(long cents) {
        long whole = cents / 100;
        long frac = Math.abs(cents % 100);
        return String.format("%d.%02d", whole, frac);
    }
}
