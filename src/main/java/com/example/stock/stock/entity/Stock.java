package com.example.stock.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "stocks")
@IdClass(StockId.class)
public class Stock {

  @Id
  @Column(name = "tenant_id")
  private long tenantId;

  @Id
  @Column(name = "item_id")
  private long itemId;

  @Column(nullable = false)
  private long quantity;

  protected Stock() {}

  public long getQuantity() {
    return quantity;
  }
}
