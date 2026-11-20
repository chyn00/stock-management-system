package com.example.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class StockManagementApplicationTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.12")
          .withDatabaseName("stock_management")
          .withUsername("stock_app")
          .withPassword("test_password")
          .withCommand("postgres", "-c", "timezone=Asia/Seoul");

  @Test
  void startsWithPostgreSqlAndFlyway() {
    try (ConfigurableApplicationContext context =
        SpringApplication.run(
            StockManagementApplication.class,
            "--server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword())) {
      JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
      String database = jdbcTemplate.queryForObject("SELECT current_database()", String.class);

      assertEquals("stock_management", database);
      assertEquals("1", context.getBean(Flyway.class).info().current().getVersion().toString());
    }
  }
}
