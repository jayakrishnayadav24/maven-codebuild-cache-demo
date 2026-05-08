package com.example.productionapp.service;

import com.example.productionapp.dto.ProductDto;
import com.example.productionapp.entity.Product;
import com.example.productionapp.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Cacheable("products")
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    @Cacheable(value = "product", key = "#id")
    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
    }

    @CacheEvict(value = {"products", "product"}, allEntries = true)
    public Product save(ProductDto dto) {
        Product product = new Product();
        product.setName(dto.getName());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        return productRepository.save(product);
    }

    @CacheEvict(value = {"products", "product"}, allEntries = true)
    public void delete(Long id) {
        productRepository.deleteById(id);
    }
}
