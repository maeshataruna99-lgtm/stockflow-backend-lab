package dev.stockflow.gateway.config;

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
 * Every Redis call of the rate limiter has a timeout. Without one, Lettuce waits 60 seconds per command, so a
 * Redis outage made every request wait that long before the limiter gave up and let it through (live run,
 * docs/STATUS.md). Read from the file: src/test/resources/application.yml shadows the main one on the classpath.
 */
class RedisTimeoutConfigTest {

    private static Duration configured(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertThat(value).as(key).isNotBlank();
        return DurationStyle.detectAndParse(value);
    }

    @Test
    @DisplayName("Redis command and connect timeouts are set and short")
    void redisTimeoutsAreConfigured() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("src/main/resources/application.yml")));
        Properties properties = yaml.getObject();

        assertThat(configured(properties, "spring.data.redis.timeout")).isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(configured(properties, "spring.data.redis.connect-timeout")).isLessThanOrEqualTo(Duration.ofSeconds(1));
    }
}
