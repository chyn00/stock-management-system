package com.example.stock.item.repository;

import com.example.stock.item.entity.Item;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends Repository<Item, Long> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO items (tenant_id, sku, name)
          VALUES (:tenantId, :sku, :name)
          ON CONFLICT (tenant_id, sku) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("tenantId") long tenantId,
      @Param("sku") String sku,
      @Param("name") String name);

  Optional<Item> findByTenantIdAndSku(long tenantId, String sku);

}
