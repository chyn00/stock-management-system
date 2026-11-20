package com.example.stock.stock.api;

import com.example.stock.stock.api.dto.InboundRequest;
import com.example.stock.stock.api.dto.OutboundRequest;
import com.example.stock.stock.api.dto.StockResponse;
import com.example.stock.stock.service.InboundService;
import com.example.stock.stock.service.OutboundService;
import com.example.stock.stock.service.StockQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

  private final InboundService inboundService;
  private final OutboundService outboundService;
  private final StockQueryService stockQueryService;

  public StockController(
      InboundService inboundService,
      OutboundService outboundService,
      StockQueryService stockQueryService) {
    this.inboundService = inboundService;
    this.outboundService = outboundService;
    this.stockQueryService = stockQueryService;
  }

  @PostMapping("/inbound")
  public StockResponse inbound(@Valid @RequestBody InboundRequest request) {
    return inboundService.receive(request);
  }

  @PostMapping("/outbound")
  public StockResponse outbound(@Valid @RequestBody OutboundRequest request) {
    return outboundService.ship(request);
  }

  @GetMapping("/{itemId}")
  public StockResponse getStock(@PathVariable long itemId) {
    return stockQueryService.getStock(itemId);
  }
}
