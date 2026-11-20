package com.example.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class StockConcurrencyIntegrationTest {

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.12");

  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static ObjectMapper mapper;
  private static HttpClient client;
  private static URI endpoint;

  @BeforeAll
  static void startApplication() {
    context =
        SpringApplication.run(
            StockManagementApplication.class,
            "--server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--stock.tenant-id=1");
    jdbc = context.getBean(JdbcTemplate.class);
    mapper = context.getBean(ObjectMapper.class);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String port = context.getEnvironment().getRequiredProperty("local.server.port");
    endpoint = URI.create("http://localhost:" + port + "/api/stocks/inbound");
  }

  @AfterAll
  static void stopApplication() {
    if (client != null) {
      client.close();
    }
    if (context != null) {
      context.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void handlesConcurrentInboundWithoutDuplicatingItem(boolean existing) throws Exception {
    String sku = newSku();
    int initialQuantity = existing ? 3 : 0;
    if (existing) {
      assertEquals(200, inbound(sku, initialQuantity, null, UUID.randomUUID()).statusCode());
    }
    int requestCount = 8;
    List<HttpResponse<String>> responses =
        concurrentRequests(requestCount, () -> inbound(sku, 1, null, UUID.randomUUID()));
    Set<Long> itemIds = new HashSet<>();
    for (HttpResponse<String> response : responses) {
      assertEquals(200, response.statusCode(), response.body());
      itemIds.add(mapper.readTree(response.body()).path("id").asLong());
    }
    assertEquals(1, itemIds.size());
    assertQuantityAndMovements(sku, initialQuantity + requestCount, existing ? requestCount + 1 : requestCount);
  }

  @Test
  void concurrentOutboundNeverMakesStockNegative() throws Exception {
    String sku = newSku();
    assertEquals(200, inbound(sku, 5, null, UUID.randomUUID()).statusCode());

    List<HttpResponse<String>> responses =
        concurrentRequests(8, () -> outbound(sku, 1, UUID.randomUUID()));

    assertEquals(5, responses.stream().filter(response -> response.statusCode() == 200).count());
    assertEquals(3, responses.stream().filter(response -> response.statusCode() == 409).count());
    assertQuantityAndMovements(sku, 0, 6);
  }

  @Test
  void concurrentInboundAndOutboundKeepStockAndMovementTotalAligned() throws Exception {
    String sku = newSku();
    int requestCount = 8;
    assertEquals(200, inbound(sku, requestCount, null, UUID.randomUUID()).statusCode());

    List<CheckedSupplier<HttpResponse<String>>> requests = new ArrayList<>();
    for (int i = 0; i < requestCount; i++) {
      requests.add(() -> inbound(sku, 1, null, UUID.randomUUID()));
      requests.add(() -> outbound(sku, 1, UUID.randomUUID()));
    }
    List<HttpResponse<String>> responses = concurrentRequests(requests);

    assertTrue(responses.stream().allMatch(response -> response.statusCode() == 200));
    assertQuantityAndMovements(sku, requestCount, 1 + requestCount * 2);
  }

  private HttpResponse<String> inbound(String sku, long quantity, String name, UUID requestId)
      throws Exception {
    var body = mapper.createObjectNode();
    body.put("sku", sku);
    body.put("quantity", quantity);
    body.put("name", name);
    body.put("requestId", requestId.toString());
    return post(mapper.writeValueAsString(body));
  }

  private HttpResponse<String> outbound(String sku, long quantity, UUID requestId)
      throws Exception {
    var body = mapper.createObjectNode();
    body.put("sku", sku);
    body.put("quantity", quantity);
    body.put("requestId", requestId.toString());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint.toString().replace("/inbound", "/outbound")))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private List<HttpResponse<String>> concurrentRequests(
      int count, CheckedSupplier<HttpResponse<String>> request) throws Exception {
    List<CheckedSupplier<HttpResponse<String>>> requests = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      requests.add(request);
    }
    return concurrentRequests(requests);
  }

  private List<HttpResponse<String>> concurrentRequests(
      List<CheckedSupplier<HttpResponse<String>>> requests) throws Exception {
    int count = requests.size();
    CountDownLatch ready = new CountDownLatch(count);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(count);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try {
      for (CheckedSupplier<HttpResponse<String>> request : requests) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 요청 시작 대기 시간 초과");
                  }
                  return request.get();
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      List<HttpResponse<String>> responses = new ArrayList<>();
      for (Future<HttpResponse<String>> future : futures) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private void assertQuantityAndMovements(String sku, long quantity, long movementCount) {
    Long itemId =
        jdbc.queryForObject(
            "SELECT id FROM items WHERE tenant_id = 1 AND sku = ?", Long.class, sku);
    assertEquals(
        quantity,
        jdbc.queryForObject(
            "SELECT quantity FROM stocks WHERE tenant_id = 1 AND item_id = ?",
            Long.class,
            itemId));
    assertEquals(
        quantity,
        jdbc.queryForObject(
            "SELECT sum(quantity_delta) FROM stock_movements"
                + " WHERE tenant_id = 1 AND item_id = ?",
            Long.class,
            itemId));
    assertEquals(
        movementCount,
        jdbc.queryForObject(
            "SELECT count(*) FROM stock_movements WHERE tenant_id = 1 AND item_id = ?",
            Long.class,
            itemId));
  }

  private String newSku() {
    return "SKU-" + UUID.randomUUID();
  }

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }
}
