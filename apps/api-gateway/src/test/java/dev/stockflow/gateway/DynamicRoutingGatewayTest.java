package dev.stockflow.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureWireMock(port = 0)
class DynamicRoutingGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("Routes /api/v1/products to CATALOG-SERVICE")
    void routesProductsToCatalogService() {
        stubFor(get(urlEqualTo("/api/v1/products"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("[{\"id\":1,\"sku\":\"PROD-001\",\"name\":\"Test Product\"}]")
                        .withStatus(200)));

        webTestClient.get()
                .uri("/api/v1/products")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$[0].sku").isEqualTo("PROD-001");
    }

    @Test
    @DisplayName("Routes /api/v1/products/{id} to CATALOG-SERVICE")
    void routesProductByIdToCatalogService() {
        stubFor(get(urlEqualTo("/api/v1/products/42"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"id\":42,\"sku\":\"PROD-042\",\"name\":\"Single Product\"}")
                        .withStatus(200)));

        webTestClient.get()
                .uri("/api/v1/products/42")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sku").isEqualTo("PROD-042");
    }

    @Test
    @DisplayName("Routes POST /api/v1/products to CATALOG-SERVICE")
    void routesPostProductToCatalogService() {
        stubFor(post(urlEqualTo("/api/v1/products"))
                .withRequestBody(equalTo("{\"sku\":\"NEW-SKU\",\"name\":\"New Item\"}"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"id\":10,\"sku\":\"NEW-SKU\",\"name\":\"New Item\"}")
                        .withStatus(201)));

        webTestClient.post()
                .uri("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sku\":\"NEW-SKU\",\"name\":\"New Item\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo(10);
    }

    @Test
    @DisplayName("Routes /api/v1/categories to CATALOG-SERVICE")
    void routesCategoriesToCatalogService() {
        stubFor(get(urlEqualTo("/api/v1/categories"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("[{\"id\":1,\"code\":\"CAT-01\",\"name\":\"Category 1\"}]")
                        .withStatus(200)));

        webTestClient.get()
                .uri("/api/v1/categories")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].code").isEqualTo("CAT-01");
    }

    @Test
    @DisplayName("Routes /api/v1/warehouses to WAREHOUSE-SERVICE")
    void routesWarehousesToWarehouseService() {
        stubFor(get(urlEqualTo("/api/v1/warehouses"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("[{\"id\":1,\"code\":\"WH-NORTH\",\"name\":\"North Hub\"}]")
                        .withStatus(200)));

        webTestClient.get()
                .uri("/api/v1/warehouses")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].code").isEqualTo("WH-NORTH");
    }

    @Test
    @DisplayName("Routes /api/v1/warehouses/{id}/locations to WAREHOUSE-SERVICE")
    void routesWarehouseLocationsToWarehouseService() {
        stubFor(get(urlEqualTo("/api/v1/warehouses/1/locations"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("[{\"id\":100,\"code\":\"LOC-A1\",\"warehouseId\":1}]")
                        .withStatus(200)));

        webTestClient.get()
                .uri("/api/v1/warehouses/1/locations")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].code").isEqualTo("LOC-A1");
    }

    @Test
    @DisplayName("Safeguard: gateway's own actuator health endpoint is handled locally")
    void gatewayActuatorHealthHandledLocally() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Safeguard: unknown paths are not routed and return 404")
    void unknownPathReturns404() {
        webTestClient.get()
                .uri("/api/v1/unknown")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Safeguard: downstream internal actuator endpoints are not publicly routed")
    void downstreamInternalActuatorNotRouted() {
        webTestClient.get()
                .uri("/api/v1/products/actuator/env")
                .exchange()
                .expectStatus().isNotFound();
    }
}
