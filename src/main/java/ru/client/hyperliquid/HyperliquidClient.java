package ru.client.hyperliquid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.OrderResult;
import ru.dto.exchanges.hyperliquid.*;
import ru.dto.funding.hyperliquid.HyperliquidFundingResponse;
import ru.dto.funding.lighter.LighterFundingRatesResponse;
import ru.dto.funding.lighter.LighterFundingResponse;
import ru.utils.HyperliquidParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "exchanges.hyperliquid")
public class HyperliquidClient {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private final HttpClient localHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HyperliquidParser hyperliquidParser = new HyperliquidParser();
    private String baseUrl;
    private static final String URL = "https://api.hyperliquid.xyz";

    public HyperliquidClient(ObjectMapper objectMapper, CloseableHttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    private String executePublicPost(String endpoint, Object body) {
        try {
            URI uri = new URIBuilder(URL + endpoint).build();

            HttpPost post = new HttpPost(uri);
            post.setHeader("Content-Type", "application/json");

            String jsonBody = objectMapper.writeValueAsString(body);
            post.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));

            log.info("Hyperliquid Public POST {}", uri);
            log.info("Hyperliquid Public POST body {}", jsonBody);

            try (CloseableHttpResponse resp = httpClient.execute(post)) {
                int code = resp.getCode();
                String responseBody = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (code == 200) {
                    return responseBody;
                }

                log.error("Hyperliquid Public POST error code={}, body={}", code, responseBody);
                return null;
            }
        } catch (Exception e) {
            log.error("Hyperliquid Public POST error {}", endpoint, e);
            return null;
        }
    }



    public List<HyperliquidFundingResponse> getFundingList() {
        try {
            String response = executePublicPost("/info", Map.of("type", "metaAndAssetCtxs"));

            if (response == null) {
                return null;
            }

            if(!response.isEmpty()) {
                log.info("[LighterController] Got response for funding rates lists");
            } else {
                log.info("[LighterController] Response for funding rates lists is empty");
                return new ArrayList<>();
            }

            return hyperliquidParser.parseMetaAndAssetCtxs(response);

        } catch (Exception e) {
            log.error(e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Markets
     */
    public List<HyperliquidMarket> getMarkets() {
        String url = baseUrl + "/markets";
        try {
            HttpResponse<String> response = get(url);
            if (response.statusCode() != 200) {
                log.error("[Hyperliquid] Markets API error: {}", response.statusCode());
                return List.of();
            }
            HyperliquidMarketsResponse dto = objectMapper.readValue(response.body(), HyperliquidMarketsResponse.class);
            log.info("[Hyperliquid] Loaded {} markets", dto.getCount());
            return dto.getData() != null ? dto.getData() : List.of();
        } catch (Exception e) {
            log.error("[Hyperliquid] Failed to get markets", e);
            return List.of();
        }
    }

    /**
     * Balance
     */
    public Double getBalance() {
        String url = baseUrl + "/balance";
        try {
            HttpResponse<String> response = get(url);
            if (response.statusCode() != 200) {
                log.error("[Hyperliquid] Balance API error: {}", response.statusCode());
                return 0.0;
            }
            HyperliquidBalanceDto dto = objectMapper.readValue(response.body(), HyperliquidBalanceDto.class);
            if (!"OK".equals(dto.getStatus())) return 0.0;
            double balance = Double.parseDouble(dto.getData().getAvailableForTrade());
            log.info("[Hyperliquid] Balance: {}", balance);
            return balance;
        } catch (Exception e) {
            log.error("[Hyperliquid] Failed to get balance", e);
            return 0.0;
        }
    }

    public Double getEquity() {
        String url = baseUrl + "/balance";
        try {
            HttpResponse<String> response = get(url);
            if (response.statusCode() != 200) return 0.0;
            HyperliquidBalanceDto dto = objectMapper.readValue(response.body(), HyperliquidBalanceDto.class);
            if (!"OK".equalsIgnoreCase(dto.getStatus())) return 0.0;
            double equity = Double.parseDouble(dto.getData().getEquity());
            log.info("[Hyperliquid] Equity: {}", equity);
            return equity;
        } catch (Exception e) {
            log.error("[Hyperliquid] Failed to get equity", e);
            return 0.0;
        }
    }

    public Double getMarginUsed() {
        String url = baseUrl + "/balance";
        try {
            HttpResponse<String> response = get(url);
            if (response.statusCode() != 200) return 0.0;
            HyperliquidBalanceDto dto = objectMapper.readValue(response.body(), HyperliquidBalanceDto.class);
            if (!"OK".equalsIgnoreCase(dto.getStatus())) return 0.0;
            double marginUsed = Double.parseDouble(dto.getData().getMarginUsed());
            log.info("[Hyperliquid] Margin used: {}", marginUsed);
            return marginUsed;
        } catch (Exception e) {
            log.error("[Hyperliquid] Failed to get margin used", e);
            return 0.0;
        }
    }

    /**
     * Leverage
     */
    public String setLeverage(String market, int leverage) {
        String url = baseUrl + "/user/leverage";
        if (leverage < 1 || leverage > 50) {
            log.error("[Hyperliquid] Invalid leverage {}. Must be 1-50x", leverage);
            return null;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("leverage", leverage);
        body.put("is_cross", true);
        try {
            HttpResponse<String> response = post(url, body);
            if (response.statusCode() != 200) {
                log.error("[Hyperliquid] Leverage HTTP error: {}", response.statusCode());
                return null;
            }
            HyperliquidLeverageResponse dto = objectMapper.readValue(response.body(), HyperliquidLeverageResponse.class);
            if (!"success".equalsIgnoreCase(dto.getStatus())) {
                log.warn("[Hyperliquid] Leverage status: {}", dto.getStatus());
                return null;
            }
            log.info("[Hyperliquid] Leverage {}x set for {}", leverage, market);
            return "success";
        } catch (Exception e) {
            log.error("[Hyperliquid] Exception setting leverage", e);
            return null;
        }
    }

    public int getMaxLeverage(String symbol) {
        String url = baseUrl + "/markets/" + symbol + "/max-leverage";
        try {
            log.info("[Hyperliquid] Getting max leverage for {}", symbol);
            HttpResponse<String> response = get(url);
            if (response.statusCode() != 200) {
                log.warn("[Hyperliquid] Max leverage HTTP error {}, using default 20x", response.statusCode());
                return 20;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!"OK".equals(root.path("status").asText())) {
                log.warn("[Hyperliquid] Max leverage request failed, using default 20x");
                return 20;
            }
            int maxLeverage = root.path("max_leverage").asInt(20);
            String source   = root.path("source").asText("unknown");
            log.info("[Hyperliquid] Max leverage for {}: {}x (source={})", symbol, maxLeverage, source);
            return maxLeverage;
        } catch (IOException | InterruptedException e) {
            log.error("[Hyperliquid] Exception getting max leverage for {}, using 20x", symbol, e);
            return 20;
        } catch (Exception e) {
            log.error("[Hyperliquid] Unexpected error getting max leverage for {}, using 20x", symbol, e);
            return 20;
        }
    }

    /**
     * Orders
     */
    public HyperliquidMarketOrderResponse openMarketPosition(String market, String side, double size) {
        String url = baseUrl + "/order/market";
        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("side", side.toUpperCase());
        body.put("size", String.valueOf(size));
        try {
            HttpResponse<String> response = post(url, body);
            log.info("[Hyperliquid] Open response HTTP {}: {}", response.statusCode(), response.body());
            if (response.statusCode() != 200) {
                log.error("[Hyperliquid] Open HTTP error: {}", response.statusCode());
                return null;
            }
            HyperliquidMarketOrderResponse dto = objectMapper.readValue(response.body(), HyperliquidMarketOrderResponse.class);
            if (!"success".equalsIgnoreCase(dto.getStatus())) {
                log.error("[Hyperliquid] Open failed status: {}", dto.getStatus());
                return null;
            }
            log.info("[Hyperliquid] Position opened: {}", dto);
            return dto;
        } catch (Exception e) {
            log.error("[Hyperliquid] Exception opening position", e);
            return null;
        }
    }

    public String openPositionWithSize(String market, double size, String direction) {
        String side = direction.equalsIgnoreCase("LONG") ? "BUY" : "SELL";
        log.info("[Hyperliquid] Opening {} {} {}", size, direction, market);
        if (size <= 0) {
            log.error("Hyperliquid Invalid size: {}", size);
            return null;
        }
        return openMarketPosition(market, side, size).getOrderId();
    }

    public HyperliquidClosePositionResponse closePosition(String market, String currentSide) {
        String url = baseUrl + "/positions/close";
        Map<String, Object> body = new HashMap<>();
        body.put("market", market);
        body.put("current_side", currentSide.toUpperCase());
        try {
            HttpResponse<String> response = post(url, body);
            log.info("[Hyperliquid] Close response HTTP {}: {}", response.statusCode(), response.body());
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                log.error("[Hyperliquid] Close HTTP error: {}", response.statusCode());
                return null;
            }
            HyperliquidClosePositionResponse dto = objectMapper.readValue(response.body(), HyperliquidClosePositionResponse.class);
            if ("success".equalsIgnoreCase(dto.getStatus()) || "submitted".equalsIgnoreCase(dto.getStatus())) {
                log.info("[Hyperliquid] Position closed: {}", dto.getMessage());
                return dto;
            }
            log.error("[Hyperliquid] Close failed status: {}", dto.getStatus());
            return null;
        } catch (Exception e) {
            log.error("[Hyperliquid] Exception closing position", e);
            return null;
        }
    }

    public OrderResult closePositionWithResult(String market, String currentSide) {
        HyperliquidClosePositionResponse dto = closePosition(market, currentSide);
        if (dto != null) {
            return OrderResult.builder()
                    .exchange(ExchangeType.HYPERLIQUID)
                    .symbol(market)
                    .success(true)
                    .orderId(dto.getOrderId())
                    .message(dto.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .exitPrice(dto.getExitPrice())
                    .build();
        }
        return OrderResult.builder()
                .exchange(ExchangeType.HYPERLIQUID)
                .symbol(market)
                .success(false)
                .message("Failed to close position")
                .errorCode("CLOSE_FAILED")
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Positions
     */
    public List<HyperliquidPosition> getPositions(String market, String side) {
        StringBuilder url = new StringBuilder(baseUrl + "/positions");
        if (market != null || side != null) {
            url.append("?");
            if (market != null) url.append("market=").append(market);
            if (side != null) {
                if (market != null) url.append("&");
                url.append("side=").append(side.toUpperCase());
            }
        }
        try {
            HttpResponse<String> response = get(url.toString());
            log.info("[Hyperliquid] Positions response HTTP {}: {}", response.statusCode(), response.body());
            if (response.statusCode() != 200) {
                log.error("[Hyperliquid] Positions HTTP error: {}", response.statusCode());
                return List.of();
            }
            HyperliquidPositionsResponse dto = objectMapper.readValue(response.body(), HyperliquidPositionsResponse.class);
            return dto.getData() != null ? dto.getData() : List.of();
        } catch (Exception e) {
            log.error("[Hyperliquid] Exception getting positions", e);
            return List.of();
        }
    }

    public ClosedPnlData getClosedPnl(String symbol, long closedAtMs) {
        String url = baseUrl + "/positions/last-closed"
                + "?market=" + symbol
                + "&closed_at_ms=" + closedAtMs
                + "&window_ms=15000";
        try {
            HttpResponse<String> response = get(url);
            if (response.statusCode() != 200) {
                log.warn("[Hyperliquid] getClosedPnl HTTP error: {}", response.statusCode());
                return null;
            }
            HyperliquidClosedPnlResponse dto = objectMapper.readValue(
                    response.body(), HyperliquidClosedPnlResponse.class
            );
            if (!"OK".equals(dto.getStatus()) || dto.getData() == null) {
                log.info("[Hyperliquid] No closed fill found for {} near {}", symbol, closedAtMs);
                return null;
            }
            log.info("[Hyperliquid] Closed PnL for {}: closedPnl={}, fee={}, netPnl={}",
                    symbol, dto.getData().getClosedPnl(),
                    dto.getData().getFee(), dto.getData().getNetPnl());
            return dto.getData();
        } catch (Exception e) {
            log.warn("[Hyperliquid] Could not get closed PnL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Funding
     */
    public Double getAccumulatedFunding(String market, String side) {
        List<HyperliquidPosition> positions = getPositions(market, side);
        if (positions.isEmpty()) {
            log.warn("[Hyperliquid] No position found for {} {} — funding=0", market, side);
            return 0.0;
        }
        HyperliquidPosition pos = positions.getFirst();
        log.info("[Hyperliquid] Funding accumulated for {}: {}", market, pos.getFundingPaid());
        return Double.parseDouble(pos.getFundingPaid());
    }
//    public Double getAccumulatedFunding(String market, long openTimeMs) {
//        String url = baseUrl + "/funding/history?market=" + market + "&start_time=" + openTimeMs;
//        try {
//            HttpResponse<String> response = get(url);
//            if (response.statusCode() != 200) {
//                log.error("[Hyperliquid] Funding HTTP error: {}", response.statusCode());
//                return 0.0;
//            }
//            JsonNode root = objectMapper.readTree(response.body());
//            if (!"OK".equals(root.path("status").asText())) {
//                log.warn("[Hyperliquid] Funding status not OK: {}", root.path("status").asText());
//                return 0.0;
//            }
//            double funding = root.path("accumulated_funding").asDouble(0.0);
//            log.info("[Hyperliquid] Accumulated funding for {} since {}: {}", market, openTimeMs, funding);
//            return funding;
//        } catch (Exception e) {
//            log.error("[Hyperliquid] Error getting funding", e);
//            return 0.0;
//        }
//    }

//    public Double getAccumulatedFunding(String market) {
//        return getAccumulatedFunding(market, 0L);
//    }

    public Double getFundingRate(String market) {
        String url = baseUrl + "/markets/" + market + "/funding-rate";
        try {
            HttpResponse<String> response = get(url);
            if (response.statusCode() != 200) {
                log.error("[Hyperliquid] Funding rate HTTP error: {}", response.statusCode());
                return 0.0;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!"OK".equals(root.path("status").asText())) {
                log.warn("[Hyperliquid] Funding rate status not OK for {}", market);
                return 0.0;
            }
            double rate = root.path("funding_rate").asDouble(0.0);
            log.info("[Hyperliquid] Funding rate for {}: {}% (1h)", market, rate);
            return rate;
        } catch (Exception e) {
            log.error("[Hyperliquid] Failed to get funding rate for {}: {}", market, e.getMessage());
            return 0.0;
        }
    }

    /**
     * OrderBook
     */
    public HyperliquidOrderBookResponse getOrderBook(String market, int limit) {
        String url = baseUrl + "/markets/" + market + "/orderbook?limit=" + limit;
        try {
            HttpResponse<String> response = get(url);
            log.info("[Hyperliquid] OrderBook response HTTP {}", response.statusCode());
            if (response.statusCode() != 200) {
                log.error("[Hyperliquid] OrderBook HTTP error: {}", response.statusCode());
                return null;
            }
            HyperliquidOrderBookResponse dto = objectMapper.readValue(response.body(), HyperliquidOrderBookResponse.class);
            if (!"OK".equals(dto.getStatus())) return null;
            log.info("[Hyperliquid] OrderBook bids={}, asks={}, mid={}",
                    dto.getSummary().getBidsCount(), dto.getSummary().getAsksCount(), dto.getSummary().getMidPrice());
            return dto;
        } catch (Exception e) {
            log.error("[Hyperliquid] Failed to get orderbook", e);
            return null;
        }
    }

    public HyperliquidOrderBookResponse getOrderBook(String market) {
        return getOrderBook(market, 10);
    }

    public Double getMarkPrice(String market) {
        HyperliquidOrderBookResponse ob = getOrderBook(market, 5);
        if (ob != null && ob.getSummary() != null && ob.getSummary().getMidPrice() != null) {
            double mid = ob.getSummary().getMidPrice();
            log.info("[Hyperliquid] Mark/mid price for {} from orderbook: {}", market, mid);
            return mid;
        }
        log.warn("[Hyperliquid] Mark price unavailable for {}", market);
        return 0.0;
    }

    /**
     * Utils
     */
    public Double calculateMaxSizeForMargin(String market, double marginUsd, int leverage, boolean isBuy) {
        String url = baseUrl + "/markets/" + market + "/calculate-size";
        Map<String, Object> body = new HashMap<>();
        body.put("margin_usd", marginUsd);
        body.put("leverage", leverage);
        body.put("is_buy", isBuy);
        try {
            HttpResponse<String> response = post(url, body);
            if (response.statusCode() != 200) {
                log.error("[Hyperliquid] Calculate size HTTP error: {}", response.statusCode());
                return null;
            }
            HyperliquidCalculateSizeResponse dto = objectMapper.readValue(response.body(), HyperliquidCalculateSizeResponse.class);
            if (!"OK".equals(dto.getStatus())) {
                log.error("[Hyperliquid] Calculate size failed: {}", dto.getStatus());
                return null;
            }
            log.info("[Hyperliquid] Max size for {}USD {}x {} price={}: {}",
                    marginUsd, leverage, market, String.format("%.4f", dto.getPrice()), dto.getMaxSize());
            return dto.getMaxSize();
        } catch (Exception e) {
            log.error("[Hyperliquid] Exception calculating max size", e);
            return null;
        }
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, Object body) throws IOException, InterruptedException {
        String jsonBody = objectMapper.writeValueAsString(body);
        log.info("[Hyperliquid] POST {} body={}", url, jsonBody);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return localHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}