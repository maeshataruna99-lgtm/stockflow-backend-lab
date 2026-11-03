package dev.stockflow.warehouse.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.FileSystemResource;

/**
 * The connection pool has an explicit size and a short wait for a free connection. Read from the file:
 * src/test/resources/application.yml shadows the main one on the classpath, so the Spring tests cannot see it.
 */
class DataSourceConfigTest {

    private static final Properties MAIN_YAML = load();

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("src/main/resources/application.yml")));
        return yaml.getObject();
    }

    @Test
    @DisplayName("Pool size and the wait for a free connection are set, and the wait is short")
    void poolIsBounded() {
        assertThat(MAIN_YAML.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
        String wait = MAIN_YAML.getProperty("spring.datasource.hikari.connection-timeout");
        assertThat(wait).isNotBlank();
        assertThat(DurationStyle.detectAndParse(wait + (wait.matches("\\d+") ? "ms" : "")))
                .isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
}
