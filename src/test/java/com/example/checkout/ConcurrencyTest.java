package com.example.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest extends TestSupport {

    @BeforeEach
    void setUp() {
        resetAll();
        seedDefaults();
    }

    @Test
    void concurrentCheckoutsNeverOversell() throws Exception {
        List<String> carts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String c = createCart();
            addItem(c, "SKU-B", 1);
            carts.add(c);
        }

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger sold_out = new AtomicInteger();
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < carts.size(); i++) {
            final String cart = carts.get(i);
            final String key = "race-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                ResponseEntity<JsonNode> r = checkout(cart, null, key);
                if (r.getStatusCode() == HttpStatus.OK) { ok.incrementAndGet(); }
                else if (r.getStatusCode() == HttpStatus.CONFLICT
                        && "insufficient_inventory".equals(r.getBody().get("code").asText())) {
                    sold_out.incrementAndGet();
                }
                return r.getStatusCode().value();
            }));
        }
        start.countDown();
        for (Future<Integer> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(5);
        assertThat(sold_out.get()).isEqualTo(15);
        assertThat(productRepo.findById("SKU-B").orElseThrow().getInventory()).isZero();
        assertThat(orderRepo.count()).isEqualTo(5);
    }

    @Test
    void repeatedCheckoutsWithSameIdempotencyKeyProduceOneOrder() throws Exception {
        String cart = createCart();
        addItem(cart, "SKU-A", 3);
        String key = "same-key";

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return checkout(cart, null, key);
            }));
        }
        start.countDown();

        String orderId = null;
        for (Future<ResponseEntity<JsonNode>> f : futures) {
            ResponseEntity<JsonNode> r = f.get(30, TimeUnit.SECONDS);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            String id = r.getBody().get("orderId").asText();
            if (orderId == null) orderId = id;
            else assertThat(id).isEqualTo(orderId);
        }
        pool.shutdown();
        assertThat(orderRepo.count()).isEqualTo(1);
        assertThat(productRepo.findById("SKU-A").orElseThrow().getInventory()).isEqualTo(47);
    }

    @Test
    void concurrentCheckoutsCannotShareCoupon() throws Exception {
        var c = new com.example.checkout.domain.Coupon("SHARE-1", 10, 999L);
        couponRepo.save(c);

        String cartA = createCart(); addItem(cartA, "SKU-A", 1);
        String cartB = createCart(); addItem(cartB, "SKU-C", 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ResponseEntity<JsonNode>> fa = pool.submit(() -> { start.await(); return checkout(cartA, "SHARE-1", "ka"); });
        Future<ResponseEntity<JsonNode>> fb = pool.submit(() -> { start.await(); return checkout(cartB, "SHARE-1", "kb"); });
        start.countDown();

        var ra = fa.get(30, TimeUnit.SECONDS);
        var rb = fb.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        int successCount = 0;
        for (ResponseEntity<JsonNode> r : List.of(ra, rb)) {
            if (r.getStatusCode() == HttpStatus.OK
                    && r.getBody().get("discountCents").asLong() > 0) successCount++;
        }
        assertThat(successCount).isEqualTo(1);

        var reloaded = couponRepo.findById("SHARE-1").orElseThrow();
        assertThat(reloaded.getStatus().name()).isEqualTo("REDEEMED");
        assertThat(reloaded.getRedeemedOnOrderId()).isNotNull();
    }

    @Test
    void failedCheckoutDoesNotConsumeCoupon() {
        couponRepo.save(new com.example.checkout.domain.Coupon("KEEP-ME", 10, 998L));
        var b = productRepo.findById("SKU-B").orElseThrow();
        b.setInventory(0);
        productRepo.save(b);

        String cart = createCart();
        addItem(cart, "SKU-B", 1);
        ResponseEntity<JsonNode> r = checkout(cart, "KEEP-ME", "kf");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(couponRepo.findById("KEEP-ME").orElseThrow().getStatus().name())
                .isEqualTo("AVAILABLE");
    }
}
