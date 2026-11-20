package com.example.stock.stock.repository;

import com.example.stock.stock.entity.Stock;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends Repository<Stock, Stock.Key> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO stocks (tenant_id, item_id)
          VALUES (:tenantId, :itemId)
          ON CONFLICT (tenant_id, item_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(@Param("tenantId") long tenantId, @Param("itemId") long itemId);

  @Modifying
  @Query(
      """
      UPDATE Stock s SET s.quantity = s.quantity + :quantity
      WHERE s.tenantId = :tenantId AND s.itemId = :itemId
      """)
  int increase(
      @Param("tenantId") long tenantId,
      @Param("itemId") long itemId,
      @Param("quantity") long quantity);

  @Modifying
  @Query(
      """
      UPDATE Stock s SET s.quantity = s.quantity - :quantity
      WHERE s.tenantId = :tenantId
        AND s.itemId = :itemId
        AND s.quantity >= :quantity
      """)
  int decreaseIfAvailable(
      @Param("tenantId") long tenantId,
      @Param("itemId") long itemId,
      @Param("quantity") long quantity);

  Optional<Stock> findByTenantIdAndItemId(long tenantId, long itemId);
}
