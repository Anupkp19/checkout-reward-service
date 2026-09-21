package com.example.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class CouponMilestoneTest extends TestSupport {

    @BeforeEach
    void setUp() {
        resetAll();
        seedDefaults();
    }

    @Test
    void generateRejectedWhenNoMilestoneReached() {
        ResponseEntity<JsonNode> r = rest.postForEntity(url("/admin/coupons/generate"),
                null, JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asText()).isEqualTo("no_milestone_available");
    }

    @Test
    void oneCouponPerMilestoneAndNoDoubleGeneration() {
        placeSuccessfulOrders(3);
        ResponseEntity<JsonNode> r1 = rest.postForEntity(url("/admin/coupons/generate"),
                null, JsonNode.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r1.getBody().get("milestone").asLong()).isEqualTo(3);
        ResponseEntity<JsonNode> r2 = rest.postForEntity(url("/admin/coupons/generate"),
                null, JsonNode.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void secondCouponAvailableAfterNextNOrders() {
        placeSuccessfulOrders(6);
        JsonNode a = rest.postForEntity(url("/admin/coupons/generate"), null, JsonNode.class).getBody();
        JsonNode b = rest.postForEntity(url("/admin/coupons/generate"), null, JsonNode.class).getBody();
        assertThat(a.get("milestone").asLong()).isEqualTo(3);
        assertThat(b.get("milestone").asLong()).isEqualTo(6);
    }

    @Test
    void couponAppliesTenPercentAndOrderCantGoNegative() {
        placeSuccessfulOrders(3);
        String code = rest.postForEntity(url("/admin/coupons/generate"), null, JsonNode.class)
                .getBody().get("code").asText();

        String cart = createCart();
        addItem(cart, "SKU-A", 1);
        ResponseEntity<JsonNode> r = checkout(cart, code, "with-coupon");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("grossCents").asLong()).isEqualTo(1000L);
        assertThat(r.getBody().get("discountCents").asLong()).isEqualTo(100L);
        assertThat(r.getBody().get("netCents").asLong()).isEqualTo(900L);
    }

    @Test
    void unknownCouponRejected() {
        String cart = createCart();
        addItem(cart, "SKU-A", 1);
        ResponseEntity<JsonNode> r = checkout(cart, "NOPE-NOPE", "k-unknown");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code").asText()).isEqualTo("invalid_coupon");
    }

    @Test
    void redeemedCouponCannotBeReused() {
        placeSuccessfulOrders(3);
        String code = rest.postForEntity(url("/admin/coupons/generate"), null, JsonNode.class)
                .getBody().get("code").asText();

        String cartA = createCart(); addItem(cartA, "SKU-A", 1);
        String cartB = createCart(); addItem(cartB, "SKU-C", 1);
        assertThat(checkout(cartA, code, "kA").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> rB = checkout(cartB, code, "kB");
        assertThat(rB.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rB.getBody().get("code").asText()).isEqualTo("coupon_not_available");
    }

    private void placeSuccessfulOrders(int n) {
        for (int i = 0; i < n; i++) {
            String c = createCart();
            addItem(c, "SKU-A", 1);
            ResponseEntity<JsonNode> r = checkout(c, null, "seed-" + i + "-" + System.nanoTime());
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
