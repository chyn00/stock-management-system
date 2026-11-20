package com.example.stock.item.repository;

import com.example.stock.item.entity.Item;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ItemRepository extends Repository<Item, Long> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO items (tenant_id, sku, name)
          VALUES (?1, ?2, ?3)
          ON CONFLICT (tenant_id, sku) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(long tenantId, String sku, String name);

  Optional<Item> findByTenantIdAndSku(long tenantId, String sku);

  Optional<Item> findByTenantIdAndId(long tenantId, long id);
}
