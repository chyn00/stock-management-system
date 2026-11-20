package com.example.stock.stock.service;

import com.example.stock.common.exception.ErrorCode;
import com.example.stock.common.exception.StockApiException;
import com.example.stock.item.entity.Item;
import com.example.stock.item.repository.ItemRepository;
import com.example.stock.stock.api.dto.OutboundRequest;
import com.example.stock.stock.api.dto.StockResponse;
import com.example.stock.stock.entity.Stock;
import com.example.stock.stock.entity.StockMovement;
import com.example.stock.stock.repository.StockMovementRepository;
import com.example.stock.stock.repository.StockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboundService {

  private final ItemRepository itemRepository;
  private final StockRepository stockRepository;
  private final StockMovementRepository movementRepository;
  private final long tenantId;

  public OutboundService(
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
  public StockResponse ship(OutboundRequest request) {
    Item item =
        itemRepository
            .findByTenantIdAndSku(tenantId, request.sku())
            .orElseThrow(() -> new StockApiException(ErrorCode.ITEM_NOT_FOUND));
    int updated = stockRepository.decreaseIfAvailable(tenantId, item.getId(), request.quantity());
    if (updated != 1) {
      throw new StockApiException(ErrorCode.INSUFFICIENT_STOCK);
    }

    movementRepository.save(
        StockMovement.outbound(
            tenantId, item.getId(), request.quantity(), request.requestId(), request.reason()));
    Stock stock =
        stockRepository.findByTenantIdAndItemId(tenantId, item.getId()).orElseThrow();
    return new StockResponse(item.getId(), item.getName(), stock.getQuantity());
  }
}
