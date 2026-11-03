package dev.stockflow.warehouse.repository;

import dev.stockflow.warehouse.domain.StockLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockLevelRepository extends JpaRepository<StockLevel, Long> {

    boolean existsByLocationIdAndProductId(Long locationId, Long productId);

    /**
     * Check and subtract in one statement: PostgreSQL takes the row lock for the UPDATE, so a second withdrawal
     * waits, then re-evaluates {@code quantity >= :quantity} against the committed value. Returns 0 when the row
     * is missing or holds too little.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE stock_levels SET quantity = quantity - :quantity, updated_at = now() "
            + "WHERE id = :id AND quantity >= :quantity", nativeQuery = true)
    int withdraw(@Param("id") Long id, @Param("quantity") int quantity);
}
