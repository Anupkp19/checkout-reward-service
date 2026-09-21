package com.example.checkout.repo;

import com.example.checkout.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRepo extends JpaRepository<IdempotencyRecord, String> {
    Optional<IdempotencyRecord> findByEndpointAndKey(String endpoint, String key);
}
