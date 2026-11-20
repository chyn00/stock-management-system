package com.example.stock.stock.service;

import com.example.stock.item.entity.Item;
import com.example.stock.item.repository.ItemRepository;
import com.example.stock.stock.api.dto.InboundRequest;
import com.example.stock.stock.api.dto.StockResponse;
import com.example.stock.stock.entity.Stock;
import com.example.stock.stock.entity.StockMovement;
import com.example.stock.stock.repository.StockMovementRepository;
import com.example.stock.stock.repository.StockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboundService {

  private final ItemRepository itemRepository;
  private final StockRepository stockRepository;
  private final StockMovementRepository movementRepository;
  private final long tenantId;

  public InboundService(
      ItemRepository itemRepository,
      StockRepository stockRepository,
      StockMovementRepository movementRepository,
      @Value("${stock.tenant-id}") long tenantId) {
    this.itemRepository = itemRepository;
    this.stockRepository = stockRepository;
    this.movementRepository = movementRepository;
    this.tenantId = tenantId;
  }

  @Transactional
  public StockResponse receive(InboundRequest request) {
    itemRepository.insertIfAbsent(tenantId, request.sku(), request.name());
    Item item =
        itemRepository.findByTenantIdAndSku(tenantId, request.sku()).orElseThrow();

    stockRepository.insertIfAbsent(tenantId, item.getId());
    int updated = stockRepository.increase(tenantId, item.getId(), request.quantity());
    if (updated != 1) {
      throw new IllegalStateException("재고 갱신 대상이 존재하지 않습니다.");
    }

    movementRepository.save(
        StockMovement.inbound(
            tenantId, item.getId(), request.quantity(), request.requestId(), request.reason()));

    Stock stock =
        stockRepository.findByTenantIdAndItemId(tenantId, item.getId()).orElseThrow();
    return new StockResponse(item.getId(), item.getName(), stock.getQuantity());
  }
}
