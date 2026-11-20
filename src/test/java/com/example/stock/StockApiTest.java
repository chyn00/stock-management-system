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
class StockApiTest {

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
    endpoint = URI.create("http://localhost:" + port + "/api/v1/stocks/inbound");
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

  @Test
  void registersUnknownItemAndReceivesStockWithoutName() throws Exception {
    String sku = newSku();

    HttpResponse<String> response = inbound(sku, 7, null, UUID.randomUUID());

    assertEquals(200, response.statusCode(), response.body());
    JsonNode body = mapper.readTree(response.body());
    assertTrue(body.path("id").asLong() > 0);
    assertTrue(body.path("name").isNull());
    assertEquals(7, body.path("quantity").asLong());
    assertEquals(
        "NEEDS_REVIEW",
        jdbc.queryForObject(
            "SELECT review_status FROM items WHERE tenant_id = 1 AND sku = ?",
            String.class,
            sku));
    assertQuantityAndMovements(sku, 7, 1);
  }

  @Test
  void increasesExistingStockWithoutOverwritingItemName() throws Exception {
    String sku = newSku();
    HttpResponse<String> first = inbound(sku, 3, "기존 상품명", UUID.randomUUID());

    HttpResponse<String> second = inbound(sku, 4, "다른 이름", UUID.randomUUID());

    assertEquals(200, first.statusCode(), first.body());
    assertEquals(200, second.statusCode(), second.body());
    JsonNode firstBody = mapper.readTree(first.body());
    JsonNode secondBody = mapper.readTree(second.body());
    assertEquals(firstBody.path("id").asLong(), secondBody.path("id").asLong());
    assertEquals("기존 상품명", secondBody.path("name").asString());
    assertEquals(7, secondBody.path("quantity").asLong());
    assertQuantityAndMovements(sku, 7, 2);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void rejectsNonPositiveQuantity(long quantity) throws Exception {
    String sku = newSku();

    HttpResponse<String> response = inbound(sku, quantity, null, UUID.randomUUID());

    assertEquals(400, response.statusCode(), response.body());
    assertEquals("INVALID_REQUEST", mapper.readTree(response.body()).path("code").asString());
    assertEquals(0, itemCount(sku));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{",
        "{\"sku\":\"A\",\"quantity\":1}",
        "{\"sku\":\"A\",\"quantity\":1,\"requestId\":\"invalid\"}",
        "{\"sku\":\" \",\"quantity\":1,"
            + "\"requestId\":\"11111111-1111-1111-1111-111111111111\"}",
        "{\"sku\":\"A\",\"quantity\":1.5,"
            + "\"requestId\":\"11111111-1111-1111-1111-111111111111\"}"
      })
  void rejectsInvalidRequest(String json) throws Exception {
    HttpResponse<String> response = post(json);

    assertEquals(400, response.statusCode(), response.body());
    assertEquals("INVALID_REQUEST", mapper.readTree(response.body()).path("code").asString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void handlesConcurrentInboundWithoutDuplicatingItem(boolean existing) throws Exception {
    String sku = newSku();
    int initialQuantity = existing ? 3 : 0;
    if (existing) {
      assertEquals(200, inbound(sku, initialQuantity, null, UUID.randomUUID()).statusCode());
    }
    int requests = 8;
    List<HttpResponse<String>> responses =
        concurrentRequests(requests, () -> inbound(sku, 1, null, UUID.randomUUID()));
    Set<Long> itemIds = new HashSet<>();
    for (HttpResponse<String> response : responses) {
      assertEquals(200, response.statusCode(), response.body());
      itemIds.add(mapper.readTree(response.body()).path("id").asLong());
    }
    assertEquals(1, itemIds.size());

    assertQuantityAndMovements(sku, initialQuantity + requests, existing ? requests + 1 : requests);
  }

  @Test
  void rollsBackStockIncreaseOnConflictingRequestId() throws Exception {
    String sku = newSku();
    UUID requestId = UUID.randomUUID();
    assertEquals(200, inbound(sku, 2, null, requestId).statusCode());

    HttpResponse<String> response = inbound(sku, 9, null, requestId);

    assertEquals(409, response.statusCode(), response.body());
    assertEquals("REQUEST_CONFLICT", mapper.readTree(response.body()).path("code").asString());
    assertQuantityAndMovements(sku, 2, 1);
  }

  @Test
  void rollsBackNewItemWhenMovementInsertFails() throws Exception {
    UUID requestId = UUID.randomUUID();
    String firstSku = newSku();
    String secondSku = newSku();
    assertEquals(200, inbound(firstSku, 2, null, requestId).statusCode());

    HttpResponse<String> response = inbound(secondSku, 1, null, requestId);

    assertEquals(409, response.statusCode(), response.body());
    assertEquals(0, itemCount(secondSku));
    assertQuantityAndMovements(firstSku, 2, 1);
  }

  @Test
  void decreasesStockAndReturnsCurrentQuantity() throws Exception {
    String sku = newSku();
    assertEquals(200, inbound(sku, 9, "상품", UUID.randomUUID()).statusCode());

    HttpResponse<String> response = outbound(sku, 4, UUID.randomUUID());

    assertEquals(200, response.statusCode(), response.body());
    JsonNode body = mapper.readTree(response.body());
    assertEquals("상품", body.path("name").asString());
    assertEquals(5, body.path("quantity").asLong());
    assertQuantityAndMovements(sku, 5, 2);
  }

  @Test
  void rejectsOutboundForUnknownItem() throws Exception {
    HttpResponse<String> response = outbound(newSku(), 1, UUID.randomUUID());

    assertEquals(404, response.statusCode(), response.body());
    assertEquals("ITEM_NOT_FOUND", mapper.readTree(response.body()).path("code").asString());
  }

  @Test
  void rejectsOutboundWhenStockIsInsufficient() throws Exception {
    String sku = newSku();
    assertEquals(200, inbound(sku, 3, null, UUID.randomUUID()).statusCode());

    HttpResponse<String> response = outbound(sku, 4, UUID.randomUUID());

    assertEquals(409, response.statusCode(), response.body());
    assertEquals("INSUFFICIENT_STOCK", mapper.readTree(response.body()).path("code").asString());
    assertQuantityAndMovements(sku, 3, 1);
  }

  @Test
  void readsCurrentStockByItemId() throws Exception {
    String sku = newSku();
    HttpResponse<String> inbound = inbound(sku, 6, "조회 상품", UUID.randomUUID());
    long itemId = mapper.readTree(inbound.body()).path("id").asLong();

    HttpResponse<String> response = getStock(itemId);

    assertEquals(200, response.statusCode(), response.body());
    JsonNode body = mapper.readTree(response.body());
    assertEquals(itemId, body.path("id").asLong());
    assertEquals("조회 상품", body.path("name").asString());
    assertEquals(6, body.path("quantity").asLong());
  }

  @Test
  void returnsNotFoundWhenReadingUnknownItem() throws Exception {
    HttpResponse<String> response = getStock(Long.MAX_VALUE);

    assertEquals(404, response.statusCode(), response.body());
  }

  @Test
  void publishesOpenApiDocsForVersionedEndpoints() throws Exception {
    URI docsUri =
        URI.create(
            endpoint.toString().replace("/api/v1/stocks/inbound", "/v3/api-docs/stock-v1"));
    HttpRequest request =
        HttpRequest.newBuilder(docsUri).GET().build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    JsonNode body = mapper.readTree(response.body());
    assertEquals("v1", body.path("info").path("version").asString());
    assertTrue(body.path("paths").has("/api/v1/stocks/inbound"));
    assertTrue(body.path("paths").has("/api/v1/stocks/outbound"));
  }

  @Test
  void concurrentOutboundNeverMakesStockNegative() throws Exception {
    String sku = newSku();
    int initialQuantity = 5;
    assertEquals(200, inbound(sku, initialQuantity, null, UUID.randomUUID()).statusCode());

    List<HttpResponse<String>> responses =
        concurrentRequests(8, () -> outbound(sku, 1, UUID.randomUUID()));

    assertEquals(5, responses.stream().filter(response -> response.statusCode() == 200).count());
    assertEquals(3, responses.stream().filter(response -> response.statusCode() == 409).count());
    assertQuantityAndMovements(sku, 0, 6);
  }

  @Test
  void concurrentInboundAndOutboundKeepStockAndMovementTotalAligned() throws Exception {
    String sku = newSku();
    int concurrentRequests = 8;
    assertEquals(200, inbound(sku, concurrentRequests, null, UUID.randomUUID()).statusCode());

    List<CheckedSupplier<HttpResponse<String>>> requests = new ArrayList<>();
    for (int i = 0; i < concurrentRequests; i++) {
      requests.add(() -> inbound(sku, 1, null, UUID.randomUUID()));
      requests.add(() -> outbound(sku, 1, UUID.randomUUID()));
    }
    List<HttpResponse<String>> responses = concurrentRequests(requests);

    assertTrue(responses.stream().allMatch(response -> response.statusCode() == 200));
    assertQuantityAndMovements(sku, concurrentRequests, 1 + concurrentRequests * 2);
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

  private HttpResponse<String> post(String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> outbound(String sku, long quantity, UUID requestId)
      throws Exception {
    var body = mapper.createObjectNode();
    body.put("sku", sku);
    body.put("quantity", quantity);
    body.put("requestId", requestId.toString());
    return send("/outbound", mapper.writeValueAsString(body));
  }

  private HttpResponse<String> getStock(long itemId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint.toString().replace("/inbound", "/" + itemId)))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> send(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint.toString().replace("/inbound", path)))
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

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }

  private void assertQuantityAndMovements(String sku, long quantity, long movements) {
    assertEquals(1, itemCount(sku));
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
        movements,
        jdbc.queryForObject(
            "SELECT count(*) FROM stock_movements WHERE tenant_id = 1 AND item_id = ?",
            Long.class,
            itemId));
  }

  private long itemCount(String sku) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM items WHERE tenant_id = 1 AND sku = ?", Long.class, sku);
  }

  private String newSku() {
    return "SKU-" + UUID.randomUUID();
  }
}
