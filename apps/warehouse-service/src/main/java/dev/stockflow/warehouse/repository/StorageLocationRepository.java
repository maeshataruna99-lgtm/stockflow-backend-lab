package dev.stockflow.warehouse.repository;

import dev.stockflow.warehouse.domain.StorageLocation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, Long> {

    /**
     * Paginated locations of one warehouse, ordered by id (insertion
     * order). The caller must prove the warehouse exists first - a
     * missing warehouse is a documented 404, not an empty page.
     */
    Page<StorageLocation> findByWarehouseId(Long warehouseId, Pageable pageable);

    boolean existsByWarehouseIdAndCode(Long warehouseId, String code);
}
