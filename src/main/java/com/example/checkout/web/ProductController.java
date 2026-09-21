package com.example.checkout.web;

import com.example.checkout.domain.Product;
import com.example.checkout.repo.ProductRepo;
import com.example.checkout.web.dto.Dtos.ProductView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductRepo products;

    public ProductController(ProductRepo products) {
        this.products = products;
    }

    @GetMapping
    public List<ProductView> list() {
        return products.findAll().stream()
                .sorted(Comparator.comparing(Product::getId))
                .map(p -> new ProductView(p.getId(), p.getName(), p.getUnitPriceCents(), p.getInventory()))
                .toList();
    }
}
