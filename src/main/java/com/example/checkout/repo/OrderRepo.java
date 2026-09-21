package com.example.checkout.repo;

import com.example.checkout.domain.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepo extends JpaRepository<Order, String> {

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findById(String id);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findByCartId(String cartId);

    long count();
}
