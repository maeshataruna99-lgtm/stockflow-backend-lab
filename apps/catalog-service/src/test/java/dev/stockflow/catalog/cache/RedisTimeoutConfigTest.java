package dev.stockflow.catalog.cache;

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
 * Every Redis call of the product cache has a timeout and the cache's Redis health check is off. Read from the
 * file: src/test/resources/application.yml shadows the main one on the classpath, so the Spring tests cannot see it.
 */
class RedisTimeoutConfigTest {

    private static final Properties MAIN_YAML = load();

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("src/main/resources/application.yml")));
        return yaml.getObject();
    }

    private static Duration configured(String key) {
        String value = MAIN_YAML.getProperty(key);
        assertThat(value).as(key).isNotBlank();
        return DurationStyle.detectAndParse(value);
    }

    @Test
    @DisplayName("Redis command and connect timeouts are set and short")
    void redisTimeoutsAreConfigured() {
        assertThat(configured("spring.data.redis.timeout")).isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(configured("spring.data.redis.connect-timeout")).isLessThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("A Redis outage does not make the service unhealthy")
    void redisHealthIndicatorIsOff() {
        assertThat(MAIN_YAML.getProperty("management.health.redis.enabled")).isEqualTo("false");
    }
}
