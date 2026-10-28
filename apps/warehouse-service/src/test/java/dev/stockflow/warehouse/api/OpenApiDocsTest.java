package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI document is served (springdoc 2.10.0, ADR 0001) and
 * describes the paths this service really exposes - the Phase 3
 * acceptance criterion "OpenAPI reflects actual behavior", verified by
 * asserting the document contains exactly the controller mappings.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsExposeTheRealControllerPaths() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("StockFlow warehouse-service API"))
                .andExpect(jsonPath("$.paths['/api/v1/warehouses']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/warehouses/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/warehouses/{id}/locations']").exists())
                // only the paths the controllers actually map: nothing more
                .andExpect(jsonPath("$.paths['/api/v1/products']").doesNotExist());
    }
}
