package com.example.checkout;

import com.example.checkout.domain.Product;
import com.example.checkout.repo.CouponRepo;
import com.example.checkout.repo.IdempotencyRepo;
import com.example.checkout.repo.OrderRepo;
import com.example.checkout.repo.ProductRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class TestSupport {

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected ProductRepo productRepo;
    @Autowired protected OrderRepo orderRepo;
    @Autowired protected CouponRepo couponRepo;
    @Autowired protected IdempotencyRepo idempotencyRepo;
    @Autowired protected ObjectMapper mapper;

    protected String url(String path) { return "http://localhost:" + port + path; }

    protected void resetAll() {
        idempotencyRepo.deleteAll();
        orderRepo.deleteAll();
        couponRepo.deleteAll();
        productRepo.deleteAll();
    }

    protected Product seedProduct(String id, String name, long priceCents, int inv) {
        return productRepo.save(new Product(id, name, priceCents, inv));
    }

    protected void seedDefaults() {
        seedProduct("SKU-A", "Widget A", 1000, 50);
        seedProduct("SKU-B", "Widget B", 250, 5);
        seedProduct("SKU-C", "Widget C", 999, 100);
    }

    protected String createCart() {
        ResponseEntity<JsonNode> r = rest.postForEntity(url("/carts"), null, JsonNode.class);
        return r.getBody().get("cartId").asText();
    }

    protected ResponseEntity<JsonNode> addItem(String cartId, String productId, int qty) {
        return rest.postForEntity(url("/carts/" + cartId + "/items"),
                Map.of("productId", productId, "quantity", qty), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> checkout(String cartId, String couponCode, String idemKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idemKey != null) headers.set("Idempotency-Key", idemKey);
        Map<String, Object> body = new LinkedHashMap<>();
        if (couponCode != null) body.put("couponCode", couponCode);
        return rest.exchange(url("/carts/" + cartId + "/checkout"),
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected JsonNode getJson(String path) {
        return rest.getForObject(url(path), JsonNode.class);
    }
}
