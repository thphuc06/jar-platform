package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.internal.CategoryView;
import io.github.thphuc06.jars.ledger.internal.LedgerQueries;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
class CategoryController {

    private final IdentityApi identity;
    private final LedgerQueries queries;

    CategoryController(IdentityApi identity, LedgerQueries queries) {
        this.identity = identity;
        this.queries = queries;
    }

    /** System defaults (BR-3) plus the own categories of the caller, wrapped so fields can be added later. */
    @GetMapping
    CategoriesResponse listCategories() {
        return new CategoriesResponse(
                queries.categoriesOf(identity.currentUser().id()));
    }

    record CategoriesResponse(List<CategoryView> items) {}
}
