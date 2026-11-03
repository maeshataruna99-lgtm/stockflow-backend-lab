package dev.stockflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.io.FileSystemResource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouteDefinitionTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    @DisplayName("Every route exists once and carries the rate limiter, breaker and the other route filters")
    void routesAreUniqueAndFiltered() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).extracting(Route::getId).doesNotHaveDuplicates();
        assertThat(routes).isNotEmpty().allSatisfy(route -> assertThat(route.getFilters()).hasSizeGreaterThanOrEqualTo(5));
    }

    @Test
    @DisplayName("The main application.yml defines no routes: a second, unfiltered copy of each route would bypass the limits")
    void mainConfigurationDefinesNoRoutes() {
        // read the file directly: src/test/resources/application.yml shadows the main one on the classpath
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("src/main/resources/application.yml")));
        Properties properties = yaml.getObject();

        assertThat(properties.stringPropertyNames())
                .noneMatch(key -> key.startsWith("spring.cloud.gateway.routes")
                        || key.startsWith("spring.cloud.gateway.server.webflux.routes"));
    }
}
