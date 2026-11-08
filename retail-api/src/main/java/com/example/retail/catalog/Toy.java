package com.example.retail.catalog;

import java.math.BigDecimal;

public record Toy(String id, String name, String category, BigDecimal price, int stock) {}
