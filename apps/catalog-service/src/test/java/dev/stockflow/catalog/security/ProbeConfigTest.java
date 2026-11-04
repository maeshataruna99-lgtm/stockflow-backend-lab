package dev.stockflow.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/** The probes are configured in the real application.yml (src/test/resources/application.yml shadows it on the classpath). */
class ProbeConfigTest {

    private static Properties mainYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("src/main/resources/application.yml")));
        return yaml.getObject();
    }

    @Test
    @DisplayName("Probes are on; liveness is the liveness state only, readiness adds the database")
    void probesAreConfigured() {
        Properties main = mainYaml();

        assertThat(main.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(main.getProperty("management.endpoint.health.group.liveness.include")).isEqualTo("livenessState");
        assertThat(main.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState,db");
    }
}
