package com.example.productionapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class ProductDto {
    private Long id;

    @NotBlank
    private String name;

    private String description;

    @Positive
    private BigDecimal price;
}
