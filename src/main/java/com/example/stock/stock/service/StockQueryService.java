package com.example.stock.stock.service;

import com.example.stock.common.exception.ErrorCode;
import com.example.stock.common.exception.StockApiException;
import com.example.stock.item.entity.Item;
import com.example.stock.item.repository.ItemRepository;
import com.example.stock.stock.api.dto.StockResponse;
import com.example.stock.stock.entity.Stock;
import com.example.stock.stock.repository.StockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StockQueryService {

  private final ItemRepository itemRepository;
  private final StockRepository stockRepository;
  private final long tenantId;

  public StockQueryService(
      ItemRepository itemRepository,
      StockRepository stockRepository,
      @Value("${stock.tenant-id}") long tenantId) {
    this.itemRepository = itemRepository;
    this.stockRepository = stockRepository;
    this.tenantId = tenantId;
  }

  public StockResponse getStock(long itemId) {
    Item item =
        itemRepository
            .findByTenantIdAndId(tenantId, itemId)
            .orElseThrow(() -> new StockApiException(ErrorCode.ITEM_NOT_FOUND));
    Stock stock =
        stockRepository
            .findByTenantIdAndItemId(tenantId, itemId)
            .orElseThrow(() -> new StockApiException(ErrorCode.ITEM_NOT_FOUND));
    return new StockResponse(item.getId(), item.getName(), stock.getQuantity());
  }
}
