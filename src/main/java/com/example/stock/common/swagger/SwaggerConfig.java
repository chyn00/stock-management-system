package com.example.stock.common.swagger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(info = @Info(title = "Stock Management API", version = "v1"))
@Configuration
public class SwaggerConfig {

  @Bean
  public GroupedOpenApi stockApiV1() {
    return GroupedOpenApi.builder().group("stock-v1").pathsToMatch("/api/v1/**").build();
  }
}
