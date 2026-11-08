package com.example.retail.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** In-memory catalogs (same products as the Retail UI). */
@Service
public class CatalogService {

    private final List<Toy> toys = List.of(
            new Toy("t1", "Teddy Bear", "Plush", new BigDecimal("19.99"), 42),
            new Toy("t2", "Toy Car", "Vehicles", new BigDecimal("9.50"), 120),
            new Toy("t3", "Jigsaw Puzzle (500pc)", "Puzzles", new BigDecimal("14.00"), 33),
            new Toy("t4", "Yo-Yo", "Classic", new BigDecimal("4.25"), 200),
            new Toy("t5", "Robot Kit", "Building", new BigDecimal("39.00"), 15),
            new Toy("t6", "Kite", "Outdoor", new BigDecimal("12.75"), 28));

    private final List<Flower> flowers = List.of(
            new Flower("f1", "Red Roses (dozen)", "Red", new BigDecimal("34.99"), 60),
            new Flower("f2", "Tulip Bouquet", "Mixed", new BigDecimal("22.00"), 45),
            new Flower("f3", "Sunflowers (6)", "Yellow", new BigDecimal("18.50"), 38),
            new Flower("f4", "Lavender Bunch", "Purple", new BigDecimal("15.25"), 52),
            new Flower("f5", "Cherry Blossom Branch", "Pink", new BigDecimal("27.00"), 20),
            new Flower("f6", "Potted Succulent", "Green", new BigDecimal("11.00"), 75));

    public List<Toy> toys() { return toys; }

    public Optional<Toy> toy(String id) { return toys.stream().filter(t -> t.id().equals(id)).findFirst(); }

    public List<Flower> flowers() { return flowers; }

    public Optional<Flower> flower(String id) { return flowers.stream().filter(f -> f.id().equals(id)).findFirst(); }
}
