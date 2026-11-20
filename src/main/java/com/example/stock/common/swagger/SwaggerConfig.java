package com.example.stock.common.swagger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(info = @Info(title = "Stock Management API", version = "0.1.0-SNAPSHOT"))
@Configuration
public class SwaggerConfig {

  @Bean
  public GroupedOpenApi stockApi() {
    return GroupedOpenApi.builder().group("stock").pathsToMatch("/api/**").build();
  }
}
