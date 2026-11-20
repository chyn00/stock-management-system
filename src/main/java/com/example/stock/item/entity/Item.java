package com.example.stock.item.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "items")
public class Item {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private long tenantId;

  @Column(nullable = false, length = 100)
  private String sku;

  private String name;

  @Column(name = "review_status", nullable = false, length = 20)
  private String reviewStatus;

  protected Item() {}

  public long getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
