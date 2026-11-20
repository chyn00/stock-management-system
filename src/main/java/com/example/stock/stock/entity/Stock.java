package com.example.stock.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

@Entity
@Table(name = "stocks")
@IdClass(Stock.Key.class)
public class Stock {

  @Id
  @Column(name = "tenant_id", nullable = false)
  private long tenantId;

  @Id
  @Column(name = "item_id", nullable = false)
  private long itemId;

  @Column(nullable = false)
  private long quantity;

  protected Stock() {}

  public long getQuantity() {
    return quantity;
  }

  public record Key(long tenantId, long itemId) implements Serializable {}
}
