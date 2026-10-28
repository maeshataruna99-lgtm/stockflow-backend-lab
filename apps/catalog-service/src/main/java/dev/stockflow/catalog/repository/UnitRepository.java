package dev.stockflow.catalog.repository;

import dev.stockflow.catalog.domain.Unit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitRepository extends JpaRepository<Unit, Long> {
}
