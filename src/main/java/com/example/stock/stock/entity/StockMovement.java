package com.example.stock.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovement {

  private static final String INBOUND = "INBOUND";
  private static final String OUTBOUND = "OUTBOUND";
  private static final String API_SOURCE = "API";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private long tenantId;

  @Column(name = "item_id", nullable = false)
  private long itemId;

  @Column(name = "event_type", nullable = false, length = 20)
  private String eventType;

  @Column(name = "source_type", nullable = false, length = 20)
  private String sourceType;

  @Column(name = "quantity_delta", nullable = false)
  private long quantityDelta;

  @Column(name = "request_id", nullable = false)
  private UUID requestId;

  @Column(columnDefinition = "text")
  private String reason;

  protected StockMovement() {}

  public static StockMovement inbound(
      long tenantId, long itemId, long quantity, UUID requestId, String reason) {
    StockMovement movement = new StockMovement();
    movement.tenantId = tenantId;
    movement.itemId = itemId;
    movement.eventType = INBOUND;
    movement.sourceType = API_SOURCE;
    movement.quantityDelta = quantity;
    movement.requestId = requestId;
    movement.reason = reason;
    return movement;
  }

  public static StockMovement outbound(
      long tenantId, long itemId, long quantity, UUID requestId, String reason) {
    StockMovement movement = new StockMovement();
    movement.tenantId = tenantId;
    movement.itemId = itemId;
    movement.eventType = OUTBOUND;
    movement.sourceType = API_SOURCE;
    movement.quantityDelta = -quantity;
    movement.requestId = requestId;
    movement.reason = reason;
    return movement;
  }
}
