package dev.stockflow.warehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Storage location inside a warehouse. Maps the Flyway V1 schema: the
 * code is unique per warehouse (composite UNIQUE), and the schema has
 * no updated_at column - only creation is timestamped.
 */
@Entity
@Table(name = "storage_locations",
       uniqueConstraints = @UniqueConstraint(name = "storage_locations_warehouse_id_code_key",
                                             columnNames = {"warehouse_id", "code"}))
public class StorageLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StorageLocation() {
        // JPA
    }

    public StorageLocation(Warehouse warehouse, String code) {
        this.warehouse = warehouse;
        this.code = code;
    }

    public Long getId() {
        return id;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public String getCode() {
        return code;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
