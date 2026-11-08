package com.example.retail.web;

import java.util.List;

public record CatalogResponse<T>(String duns, String company, int count, List<T> items) {}
