package com.example.retail.web;

import com.example.retail.catalog.CatalogService;
import com.example.retail.catalog.Flower;
import com.example.retail.security.RetailSecurityProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Flower Shop catalog. Access (RETAILUI under the flower DUNS) is enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/flowers")
class FlowerController {

    private final CatalogService catalog;
    private final RetailSecurityProperties props;

    FlowerController(CatalogService catalog, RetailSecurityProperties props) {
        this.catalog = catalog;
        this.props = props;
    }

    @GetMapping
    CatalogResponse<Flower> list() {
        return new CatalogResponse<>(props.flowerDuns(), "Flower Shop", catalog.flowers().size(), catalog.flowers());
    }

    @GetMapping("/{id}")
    Flower get(@PathVariable String id) {
        return catalog.flower(id).orElseThrow(() -> new NotFoundException("No flower with id " + id));
    }
}
