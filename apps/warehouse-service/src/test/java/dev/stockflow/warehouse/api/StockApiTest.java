package dev.stockflow.warehouse.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stockflow.warehouse.AbstractWarehouseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Stock endpoints: happy path and the documented 400/404/409 contract, on the real PostgreSQL container. */
@AutoConfigureMockMvc
class StockApiTest extends AbstractWarehouseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private long createStock(int quantity) throws Exception {
        String location = mockMvc.perform(post("/api/v1/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":1,\"productId\":" + System.nanoTime() + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private static String withdrawBody(int quantity) {
        return "{\"quantity\":" + quantity + "}";
    }

    @Test
    @DisplayName("A withdrawal returns the stock row with the reduced quantity")
    void withdrawReducesQuantity() throws Exception {
        long id = createStock(10);

        mockMvc.perform(post("/api/v1/stock/" + id + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(6));
        mockMvc.perform(get("/api/v1/stock/" + id)).andExpect(jsonPath("$.quantity").value(6));
    }

    @Test
    @DisplayName("Asking for more than is in stock is a 409 and changes nothing")
    void withdrawTooMuchIsConflict() throws Exception {
        long id = createStock(3);

        mockMvc.perform(post("/api/v1/stock/" + id + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody(4)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"));
        mockMvc.perform(get("/api/v1/stock/" + id)).andExpect(jsonPath("$.quantity").value(3));
    }

    @Test
    @DisplayName("A missing stock row is a 404 and a quantity below 1 is a 400")
    void notFoundAndValidation() throws Exception {
        mockMvc.perform(post("/api/v1/stock/987654/withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody(1)))
                .andExpect(status().isNotFound());
        long id = createStock(5);
        mockMvc.perform(post("/api/v1/stock/" + id + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody(0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Stock for an unknown location is a 404; the same product and location twice is a 409")
    void createContract() throws Exception {
        mockMvc.perform(post("/api/v1/stock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":987654,\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/stock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":1,\"productId\":777001,\"quantity\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/stock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":1,\"productId\":777001,\"quantity\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("The unsafe lab endpoint does not exist unless the lab property is set")
    void unsafeEndpointIsOffByDefault() throws Exception {
        long id = createStock(5);

        mockMvc.perform(post("/api/v1/stock/" + id + "/withdraw-unsafe")
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody(1)))
                .andExpect(status().isNotFound());
    }
}
