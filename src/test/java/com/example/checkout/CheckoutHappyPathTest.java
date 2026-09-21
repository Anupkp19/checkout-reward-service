package com.example.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutHappyPathTest extends TestSupport {

    @BeforeEach
    void setUp() {
        resetAll();
        seedDefaults();
    }

    @Test
    void createCartAddItemsAndCheckout() {
        String cart = createCart();
        addItem(cart, "SKU-A", 2);
        addItem(cart, "SKU-C", 1);
        ResponseEntity<JsonNode> r = checkout(cart, null, "k1");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode o = r.getBody();
        assertThat(o.get("grossCents").asLong()).isEqualTo(2999L);
        assertThat(o.get("discountCents").asLong()).isZero();
        assertThat(o.get("netCents").asLong()).isEqualTo(2999L);
        assertThat(o.get("lines")).hasSize(2);

        assertThat(productRepo.findById("SKU-A").orElseThrow().getInventory()).isEqualTo(48);
        assertThat(productRepo.findById("SKU-C").orElseThrow().getInventory()).isEqualTo(99);

        JsonNode cartView = getJson("/carts/" + cart);
        assertThat(cartView.get("status").asText()).isEqualTo("CHECKED_OUT");
    }

    @Test
    void addingUnknownProductIsRejected() {
        String cart = createCart();
        ResponseEntity<JsonNode> r = addItem(cart, "SKU-NOPE", 1);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code").asText()).isEqualTo("unknown_product");
    }

    @Test
    void addingZeroQuantityIsRejected() {
        String cart = createCart();
        ResponseEntity<JsonNode> r = addItem(cart, "SKU-A", 0);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void emptyCartCannotCheckout() {
        String cart = createCart();
        ResponseEntity<JsonNode> r = checkout(cart, null, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code").asText()).isEqualTo("empty_cart");
    }

    @Test
    void secondCheckoutOnClosedCartFails() {
        String cart = createCart();
        addItem(cart, "SKU-A", 1);
        checkout(cart, null, "k1");
        ResponseEntity<JsonNode> r = checkout(cart, null, "k2");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
