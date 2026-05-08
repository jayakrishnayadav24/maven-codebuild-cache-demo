package com.example.productionapp.controller;

import com.example.productionapp.dto.ProductDto;
import com.example.productionapp.entity.Product;
import com.example.productionapp.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product management APIs")
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @Operation(summary = "Get all products")
    public List<Product> getAll() {
        return productService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID")
    public Product getById(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create a product")
    public ResponseEntity<Product> create(@Valid @RequestBody ProductDto dto) {
        return ResponseEntity.ok(productService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a product")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
