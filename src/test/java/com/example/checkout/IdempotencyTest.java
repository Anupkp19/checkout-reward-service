package com.example.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends TestSupport {

    @BeforeEach
    void setUp() {
        resetAll();
        seedDefaults();
    }

    @Test
    void sameKeySameBodyReplaysResponse() {
        String cart = createCart();
        addItem(cart, "SKU-A", 2);
        ResponseEntity<JsonNode> a = checkout(cart, null, "same-key");
        ResponseEntity<JsonNode> b = checkout(cart, null, "same-key");
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody().get("orderId")).isEqualTo(b.getBody().get("orderId"));
        assertThat(orderRepo.count()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyReturns409() {
        String cart1 = createCart();
        addItem(cart1, "SKU-A", 1);
        checkout(cart1, null, "shared");

        String cart2 = createCart();
        addItem(cart2, "SKU-A", 1);
        ResponseEntity<JsonNode> r = checkout(cart2, null, "shared");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asText()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void checkoutWithoutIdempotencyKeyStillProtectsAgainstDoubleOrder() {
        String cart = createCart();
        addItem(cart, "SKU-A", 1);
        ResponseEntity<JsonNode> a = checkout(cart, null, null);
        ResponseEntity<JsonNode> b = checkout(cart, null, null);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody().get("orderId")).isEqualTo(b.getBody().get("orderId"));
        assertThat(orderRepo.count()).isEqualTo(1);
    }
}
