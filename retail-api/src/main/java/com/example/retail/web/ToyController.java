package com.example.retail.web;

import com.example.retail.catalog.CatalogService;
import com.example.retail.catalog.Toy;
import com.example.retail.security.RetailSecurityProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Toy Shop catalog. Access (RETAILUI under the toy DUNS) is enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/toys")
class ToyController {

    private final CatalogService catalog;
    private final RetailSecurityProperties props;

    ToyController(CatalogService catalog, RetailSecurityProperties props) {
        this.catalog = catalog;
        this.props = props;
    }

    @GetMapping
    CatalogResponse<Toy> list() {
        return new CatalogResponse<>(props.toyDuns(), "Toy Shop", catalog.toys().size(), catalog.toys());
    }

    @GetMapping("/{id}")
    Toy get(@PathVariable String id) {
        return catalog.toy(id).orElseThrow(() -> new NotFoundException("No toy with id " + id));
    }
}
