package com.example.retail.catalog;

import java.math.BigDecimal;

public record Flower(String id, String name, String color, BigDecimal price, int stock) {}
