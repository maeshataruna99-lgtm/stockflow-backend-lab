package dev.stockflow.catalog.api;

import dev.stockflow.catalog.AbstractCatalogIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller + service + repository proven together against the real
 * PostgreSQL container: happy paths and every documented failure
 * contract (400 validation, 404 missing, 409 duplicate sku). The
 * container is a shared singleton, so every test asserts only on
 * fixtures it created (unique tokens) or on the immutable seed rows -
 * never on global counts or assumed sequence positions.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogProductApiTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String productBody(String sku, String name, Long categoryId, Long unitId) {
        return """
                {
                  "sku": "%s",
                  "name": "%s",
                  "description": "created by CatalogProductApiTest",
                  "categoryId": %d,
                  "unitId": %d
                }
                """.formatted(sku, name, categoryId, unitId);
    }

    private String createProduct(String sku, String name, long categoryId, long unitId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(sku, name, categoryId, unitId)))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getHeader("Location");
    }

    @Test
    void getProductReturnsSeededProduct() throws Exception {
        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sku").value("SPK-X1"))
                .andExpect(jsonPath("$.categoryId").value(1))
                .andExpect(jsonPath("$.categoryCode").value("ELECTRONICS"))
                .andExpect(jsonPath("$.unitCode").value("PCS"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void createProductReturns201WithLocationAndDurableBody() throws Exception {
        String location = createProduct("T3-201", "API Created Product", 2L, 2L);

        assertThat(location).startsWith("/api/v1/products/");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("T3-201"))
                .andExpect(jsonPath("$.categoryCode").value("HOME-KITCHEN"))
                .andExpect(jsonPath("$.unitCode").value("KG"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void createProductWithDuplicateSkuReturns409ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody("SPK-X1", "Duplicate SKU Attempt", 1L, 1L)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Duplicate resource"))
                .andExpect(jsonPath("$.detail").value("sku 'SPK-X1' already exists"));
    }

    @Test
    void createProductWithInvalidBodyReturns400WithFieldErrors() throws Exception {
        String invalid = """
                { "sku": "", "name": "", "description": "no refs" }
                """;
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.sku").isNotEmpty())
                .andExpect(jsonPath("$.errors.name").isNotEmpty())
                .andExpect(jsonPath("$.errors.categoryId").isNotEmpty())
                .andExpect(jsonPath("$.errors.unitId").isNotEmpty());
    }

    @Test
    void createProductWithUnknownCategoryReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody("T3-404", "Unknown Category Product", 999L, 1L)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("category with id 999 not found"));
    }

    @Test
    void getMissingProductReturns404ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/products/9999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("product with id 9999 not found"));
    }

    @Test
    void patchProductUpdatesProvidedFieldsDurably() throws Exception {
        String location = createProduct("T3-PATCH", "Before Patch", 1L, 1L);

        String patchBody = """
                { "name": "After Patch", "description": "patched description", "active": false }
                """;
        mockMvc.perform(patch(location)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("T3-PATCH"))
                .andExpect(jsonPath("$.name").value("After Patch"))
                .andExpect(jsonPath("$.description").value("patched description"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.categoryCode").value("ELECTRONICS"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // the update is durable: re-read through the API
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After Patch"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void patchMissingProductReturns404ProblemJson() throws Exception {
        String patchBody = """
                { "name": "No Such Product" }
                """;
        mockMvc.perform(patch("/api/v1/products/9999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void patchWithBlankNameReturns400ProblemJson() throws Exception {
        String patchBody = """
                { "name": "" }
                """;
        mockMvc.perform(patch("/api/v1/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
    }

    @Test
    void listProductsIsPaginatedAndCappedAtMaxSize() throws Exception {
        // a 500 size request is clamped to the documented maximum of 100
        mockMvc.perform(get("/api/v1/products").param("page", "0").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page.size").value(100))
                .andExpect(jsonPath("$.page.number").value(0));

        // default size without parameters is the documented default of 20
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").isNumber());
    }

    @Test
    void listProductsFiltersThroughDocumentedJpqlQuery() throws Exception {
        // self-contained fixture with a token no other test uses; the
        // seeded SPK-X1 does not match "zebra", so assertions are exact
        String location = createProduct("T3-JPQL-1", "Zebra Searchtext", 1L, 1L);

        // search over name/sku, case-insensitive
        mockMvc.perform(get("/api/v1/products").param("search", "zebra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("T3-JPQL-1"));

        // category predicate: the fixture lives in category 1, not 2
        mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", "2")
                        .param("search", "zebra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        // active predicate: the fixture starts active
        mockMvc.perform(get("/api/v1/products")
                        .param("active", "false")
                        .param("search", "zebra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(patch(location)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/products")
                        .param("active", "false")
                        .param("search", "zebra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(get("/api/v1/products")
                        .param("active", "true")
                        .param("search", "zebra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void listCategoriesIsPaginated() throws Exception {
        // categories are read-only in Phase 3 and the seed is immutable,
        // so exact assertions on the two seeded rows are stable
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].code").value("ELECTRONICS"))
                .andExpect(jsonPath("$.content[1].code").value("HOME-KITCHEN"));
    }
}
