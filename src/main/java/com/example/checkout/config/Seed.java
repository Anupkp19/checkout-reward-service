package com.example.checkout.config;

import com.example.checkout.domain.Product;
import com.example.checkout.repo.ProductRepo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration
public class Seed {

    @Bean
    @Profile("!test")
    public CommandLineRunner seedProducts(ProductRepo repo) {
        return args -> {
            if (repo.count() > 0) return;
            repo.saveAll(List.of(
                    new Product("SKU-COFFEE",    "Single-origin coffee 250g", 1499, 100),
                    new Product("SKU-KETTLE",    "Electric kettle 1.7L",      6900, 25),
                    new Product("SKU-MUG",       "Ceramic mug",                999, 200),
                    new Product("SKU-GRINDER",   "Manual burr grinder",      12900, 3),
                    new Product("SKU-FILTERS",   "Paper filters (100 pack)",   499, 500),
                    new Product("SKU-SUBSCRIBE", "Monthly bean subscription", 2999, 50)
            ));
        };
    }
}
