package com.example.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ReportingTest extends TestSupport {

    @BeforeEach
    void setUp() {
        resetAll();
        seedDefaults();
    }

    @Test
    void reportReconcilesOrdersAndCoupons() {
        for (int i = 0; i < 3; i++) {
            String c = createCart();
            addItem(c, "SKU-A", 1);
            checkout(c, null, "seed-" + i);
        }
        String code = rest.postForEntity(url("/admin/coupons/generate"), null, JsonNode.class)
                .getBody().get("code").asText();
        String c4 = createCart();
        addItem(c4, "SKU-A", 1);
        assertThat(checkout(c4, code, "seed-3").getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode report = getJson("/admin/report");

        assertThat(report.get("totalOrders").asLong()).isEqualTo(4);
        assertThat(report.get("grossRevenueCents").asLong()).isEqualTo(4000L);
        assertThat(report.get("totalDiscountsCents").asLong()).isEqualTo(100L);
        assertThat(report.get("netRevenueCents").asLong()).isEqualTo(3900L);
        assertThat(report.get("couponsGenerated").asLong()).isEqualTo(1L);
        assertThat(report.get("couponsAvailable").asLong()).isZero();
        assertThat(report.get("couponsRedeemed").asLong()).isEqualTo(1L);

        JsonNode row = report.get("salesByProduct").get(0);
        assertThat(row.get("productId").asText()).isEqualTo("SKU-A");
        assertThat(row.get("unitsSold").asLong()).isEqualTo(4L);

        JsonNode again = getJson("/admin/report");
        assertThat(again).isEqualTo(report);
    }
}
