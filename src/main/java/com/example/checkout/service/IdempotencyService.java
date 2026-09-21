package com.example.checkout.service;

import com.example.checkout.domain.IdempotencyRecord;
import com.example.checkout.error.ApiException;
import com.example.checkout.repo.IdempotencyRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final IdempotencyRepo repo;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyRepo repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public String hashRequest(Object body) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(body == null ? "" : body);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash request", e);
        }
    }

    public Optional<IdempotencyRecord> lookup(String endpoint, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return repo.findByEndpointAndKey(endpoint, key);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void store(String endpoint, String key, String requestHash,
                      int statusCode, Object responseBody, String orderId) {
        if (key == null || key.isBlank()) return;
        Optional<IdempotencyRecord> existing = repo.findByEndpointAndKey(endpoint, key);
        if (existing.isPresent()) {
            ensureMatchingHash(existing.get(), requestHash);
            return;
        }
        try {
            String body = mapper.writeValueAsString(responseBody);
            IdempotencyRecord rec = new IdempotencyRecord(
                    UUID.randomUUID().toString(), endpoint, key, requestHash,
                    statusCode, body, orderId);
            repo.saveAndFlush(rec);
        } catch (DataIntegrityViolationException e) {
            IdempotencyRecord other = repo.findByEndpointAndKey(endpoint, key)
                    .orElseThrow(() -> e);
            if (!other.getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                        "Idempotency-Key was reused with a different request body");
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize idempotent response", e);
        }
    }

    public void ensureMatchingHash(IdempotencyRecord rec, String requestHash) {
        if (!rec.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                    "Idempotency-Key was reused with a different request body");
        }
    }

    public <T> T decode(IdempotencyRecord rec, Class<T> type) {
        try {
            return mapper.readValue(rec.getResponseBody(), type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode stored idempotent response", e);
        }
    }
}

