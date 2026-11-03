package dev.stockflow.warehouse.repository;

import dev.stockflow.warehouse.domain.StockLevel;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
    @Query(value = "UPDATE stock_levels SET quantity = quantity - :quantity, version = version + 1, updated_at = now() "
            + "WHERE id = :id AND quantity >= :quantity", nativeQuery = true)
    int withdraw(@Param("id") Long id, @Param("quantity") int quantity);

    /** Optimistic write: succeeds (returns 1) only if nobody changed the row since it was read at {@code version}. */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE stock_levels SET quantity = :quantity, version = version + 1, updated_at = now() "
            + "WHERE id = :id AND version = :version", nativeQuery = true)
    int updateQuantityIfVersion(@Param("id") Long id, @Param("quantity") int quantity, @Param("version") long version);

    /** SELECT ... FOR UPDATE: other transactions that lock or write this row wait until this one ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockLevel s where s.id = :id")
    Optional<StockLevel> findByIdForUpdate(@Param("id") Long id);
}
