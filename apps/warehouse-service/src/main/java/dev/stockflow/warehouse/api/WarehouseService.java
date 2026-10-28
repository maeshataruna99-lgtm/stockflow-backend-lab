package dev.stockflow.warehouse.api;

import dev.stockflow.warehouse.api.dto.StorageLocationResponse;
import dev.stockflow.warehouse.api.dto.WarehouseRequest;
import dev.stockflow.warehouse.api.dto.WarehouseResponse;
import dev.stockflow.warehouse.api.dto.WarehouseUpdateRequest;
import dev.stockflow.warehouse.domain.StorageLocation;
import dev.stockflow.warehouse.domain.Warehouse;
import dev.stockflow.warehouse.repository.StorageLocationRepository;
import dev.stockflow.warehouse.repository.WarehouseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouse use cases. Writes enforce the documented failure contract:
 * duplicate code -> 409. The update path flushes before mapping so the
 * returned updatedAt is the flushed value, not the pre-update one
 * (lesson from catalog-service unit 3.2, evidence in docs/STATUS.md).
 */
@Service
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final StorageLocationRepository storageLocationRepository;

    WarehouseService(WarehouseRepository warehouseRepository,
                     StorageLocationRepository storageLocationRepository) {
        this.warehouseRepository = warehouseRepository;
        this.storageLocationRepository = storageLocationRepository;
    }

    @Transactional(readOnly = true)
    public Page<WarehouseResponse> list(Pageable pageable) {
        return warehouseRepository.findAll(pageable).map(WarehouseResponse::from);
    }

    @Transactional(readOnly = true)
    public WarehouseResponse get(Long id) {
        return warehouseRepository.findById(id)
                .map(WarehouseResponse::from)
                .orElseThrow(() -> ResourceNotFoundException.of("warehouse", id));
    }

    @Transactional
    public WarehouseResponse create(WarehouseRequest request) {
        if (warehouseRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException("code '" + request.code() + "' already exists");
        }
        Warehouse saved = warehouseRepository.save(new Warehouse(request.code(), request.name(), request.address()));
        return WarehouseResponse.from(saved);
    }

    @Transactional
    public WarehouseResponse update(Long id, WarehouseUpdateRequest request) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("warehouse", id));

        if (request.code() != null && !request.code().equals(warehouse.getCode())) {
            if (warehouseRepository.existsByCode(request.code())) {
                throw new DuplicateResourceException("code '" + request.code() + "' already exists");
            }
            warehouse.setCode(request.code());
        }
        if (request.name() != null) {
            warehouse.setName(request.name());
        }
        if (request.address() != null) {
            warehouse.setAddress(request.address());
        }
        if (request.active() != null) {
            warehouse.setActive(request.active());
        }

        Warehouse saved = warehouseRepository.saveAndFlush(warehouse);
        return WarehouseResponse.from(saved);
    }

    /**
     * Locations are mapped inside this transaction (open-in-view is
     * off), and a missing warehouse is a documented 404 - not an
     * empty page.
     */
    @Transactional(readOnly = true)
    public Page<StorageLocationResponse> listLocations(Long warehouseId, Pageable pageable) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw ResourceNotFoundException.of("warehouse", warehouseId);
        }
        return storageLocationRepository.findByWarehouseId(warehouseId, pageable)
                .map(StorageLocationResponse::from);
    }

    @Transactional
    public StorageLocationResponse createLocation(Long warehouseId, String code) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> ResourceNotFoundException.of("warehouse", warehouseId));
        if (storageLocationRepository.existsByWarehouseIdAndCode(warehouseId, code)) {
            throw new DuplicateResourceException(
                    "location code '" + code + "' already exists in warehouse " + warehouseId);
        }
        StorageLocation saved = storageLocationRepository.save(new StorageLocation(warehouse, code));
        return StorageLocationResponse.from(saved);
    }
}
