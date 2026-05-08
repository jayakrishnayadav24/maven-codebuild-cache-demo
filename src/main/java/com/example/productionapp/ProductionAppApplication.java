package com.example.productionapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class ProductionAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductionAppApplication.class, args);
    }
}
