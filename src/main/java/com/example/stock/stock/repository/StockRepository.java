package com.example.stock.stock.repository;

import com.example.stock.stock.entity.Stock;
import com.example.stock.stock.entity.StockId;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface StockRepository extends Repository<Stock, StockId> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO stocks (tenant_id, item_id)
          VALUES (?1, ?2)
          ON CONFLICT (tenant_id, item_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(long tenantId, long itemId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE Stock s SET s.quantity = s.quantity + ?3
      WHERE s.tenantId = ?1 AND s.itemId = ?2
      """)
  int increase(long tenantId, long itemId, long quantity);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE Stock s SET s.quantity = s.quantity - ?3
      WHERE s.tenantId = ?1 AND s.itemId = ?2 AND s.quantity >= ?3
      """)
  int decreaseIfAvailable(long tenantId, long itemId, long quantity);

  Optional<Stock> findByTenantIdAndItemId(long tenantId, long itemId);
}
