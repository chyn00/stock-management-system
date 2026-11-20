package com.example.stock.stock.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record OutboundRequest(
    @NotBlank @Size(max = 100) String sku,
    @Positive long quantity,
    @NotNull UUID requestId,
    String reason) {

  public OutboundRequest {
    if (sku != null) {
      sku = sku.strip();
    }
  }
}
