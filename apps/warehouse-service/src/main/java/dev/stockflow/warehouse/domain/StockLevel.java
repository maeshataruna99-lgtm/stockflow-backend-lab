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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Quantity of one product at one storage location. Maps the Flyway V3 schema. */
@Entity
@Table(name = "stock_levels",
       uniqueConstraints = @UniqueConstraint(name = "stock_levels_location_id_product_id_key",
                                             columnNames = {"location_id", "product_id"}))
public class StockLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private StorageLocation location;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    // read-only on purpose: the JPA save in the unsafe lab path must stay unversioned; versioned writers use SQL
    @Column(nullable = false, insertable = false, updatable = false)
    private long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockLevel() {
        // JPA
    }

    public StockLevel(StorageLocation location, Long productId, int quantity) {
        this.location = location;
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public StorageLocation getLocation() {
        return location;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
