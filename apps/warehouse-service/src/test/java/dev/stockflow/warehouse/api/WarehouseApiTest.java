package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
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
 * contract (400 validation, 404 missing, 409 duplicate code). The
 * container is a shared singleton, so every test asserts only on
 * fixtures it created (unique tokens) or on the immutable seed rows -
 * never on global counts or assumed sequence positions (lesson from
 * catalog unit 3.2, evidence in docs/STATUS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WarehouseApiTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String warehouseBody(String code, String name) {
        return """
                { "code": "%s", "name": "%s", "address": "created by WarehouseApiTest" }
                """.formatted(code, name);
    }

    private String createWarehouse(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(warehouseBody(code, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getHeader("Location");
    }

    @Test
    void getWarehouseReturnsSeededWarehouse() throws Exception {
        mockMvc.perform(get("/api/v1/warehouses/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("WH-JKT01"))
                .andExpect(jsonPath("$.name").value("Jakarta Main Warehouse"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void createWarehouseReturns201WithLocationAndDurableBody() throws Exception {
        String location = createWarehouse("T3-201", "API Created Warehouse");

        assertThat(location).startsWith("/api/v1/warehouses/");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("T3-201"))
                .andExpect(jsonPath("$.name").value("API Created Warehouse"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void createWarehouseWithDuplicateCodeReturns409ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(warehouseBody("WH-JKT01", "Duplicate Code Attempt")))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Duplicate resource"))
                .andExpect(jsonPath("$.detail").value("code 'WH-JKT01' already exists"));
    }

    @Test
    void createWarehouseWithInvalidBodyReturns400WithFieldErrors() throws Exception {
        String invalid = """
                { "code": "", "name": "" }
                """;
        mockMvc.perform(post("/api/v1/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.code").isNotEmpty())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
    }

    @Test
    void getMissingWarehouseReturns404ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/warehouses/9999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("warehouse with id 9999 not found"));
    }

    @Test
    void patchWarehouseUpdatesProvidedFieldsDurably() throws Exception {
        String location = createWarehouse("T3-PATCH", "Before Patch");

        String patchBody = """
                { "name": "After Patch", "address": "patched address", "active": false }
                """;
        mockMvc.perform(patch(location)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("T3-PATCH"))
                .andExpect(jsonPath("$.name").value("After Patch"))
                .andExpect(jsonPath("$.address").value("patched address"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // the update is durable: re-read through the API
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After Patch"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void patchMissingWarehouseReturns404ProblemJson() throws Exception {
        String patchBody = """
                { "name": "No Such Warehouse" }
                """;
        mockMvc.perform(patch("/api/v1/warehouses/9999")
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
        mockMvc.perform(patch("/api/v1/warehouses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
    }

    @Test
    void listWarehousesIsPaginatedAndCappedAtMaxSize() throws Exception {
        // a 500 size request is clamped to the documented maximum of 100
        mockMvc.perform(get("/api/v1/warehouses").param("page", "0").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page.size").value(100))
                .andExpect(jsonPath("$.page.number").value(0));

        // default size without parameters is the documented default of 20
        mockMvc.perform(get("/api/v1/warehouses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").isNumber());
    }

    @Test
    void listLocationsReturnsSeededLocationsForWarehouseOne() throws Exception {
        // the seed is immutable and warehouses are never deleted, so the
        // three seeded locations of warehouse 1 are always present
        mockMvc.perform(get("/api/v1/warehouses/1/locations"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page.size").value(20))
                // other test classes/methods may append locations to this
                // warehouse (singleton DB), but insertion order keeps the
                // three seeded rows at the front of page 0
                .andExpect(jsonPath("$.page.totalElements").isNumber())
                .andExpect(jsonPath("$.content[0].warehouseId").value(1))
                .andExpect(jsonPath("$.content[0].code").value("A-01-01"))
                .andExpect(jsonPath("$.content[2].code").value("B-02-01"))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }

    @Test
    void listLocationsForMissingWarehouseReturns404NotAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/warehouses/9999/locations"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("warehouse with id 9999 not found"));
    }

    @Test
    void createLocationReturns201AndIsUniquePerWarehouse() throws Exception {
        String warehouseLocation = createWarehouse("T3-LOC", "Location Fixture Warehouse");

        mockMvc.perform(post(warehouseLocation + "/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""" 
                                { "code": "ZZ-99-99" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ZZ-99-99"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // duplicate within the same warehouse -> 409
        mockMvc.perform(post(warehouseLocation + "/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "ZZ-99-99" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Duplicate resource"))
                .andExpect(jsonPath("$.detail")
                        .value("location code 'ZZ-99-99' already exists in warehouse " + warehouseIdOf(warehouseLocation)));

        // the same code in a DIFFERENT warehouse (the seeded one) is fine
        mockMvc.perform(post("/api/v1/warehouses/1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "ZZ-99-99" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warehouseId").value(1))
                .andExpect(jsonPath("$.code").value("ZZ-99-99"));
    }

    private static long warehouseIdOf(String location) {
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    void createLocationWithBlankCodeReturns400ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/warehouses/1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.code").isNotEmpty());
    }
}
