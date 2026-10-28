package dev.stockflow.catalog.repository;

import dev.stockflow.catalog.domain.Sku;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuRepository extends JpaRepository<Sku, Long> {
}
