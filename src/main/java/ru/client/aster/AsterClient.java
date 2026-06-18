package ru.client.aster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.dto.exchanges.ExchangeType;
import ru.dto.exchanges.OrderResult;
import ru.dto.exchanges.aster.*;
import ru.dto.funding.aster.AsterFundingResponse;
import ru.exceptions.aster.AsterApiException;
import ru.exceptions.aster.AsterIpBanException;
import ru.exceptions.aster.AsterRateLimitException;
import ru.exceptions.aster.AsterUnknownExecutionException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "exchanges.aster")
public class AsterClient {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    private String publicApiKey;
    private String privateApiKey;
    private String baseUrl;

    //Filters cache (at the start of the program)
    private final Map<String, SymbolFilter> symbolFilters = new HashMap<>();

    //Server time offset for timestamp correction
    private volatile long serverTimeOffset = 0L;

    public AsterClient(CloseableHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Initialisation
     */
    @PostConstruct
    public void init() {
        syncServerTime();
        loadSymbolFilters();
    }

    private void syncServerTime() {
        try {
            String json = executePublicGet("/fapi/v1/time", null);
            if (json == null) return;

            JsonNode node = objectMapper.readTree(json);
            long serverTime = node.path("serverTime").asLong();
            serverTimeOffset = serverTime - System.currentTimeMillis();
            log.info("[Aster] Server time synced, offset: {}ms", serverTimeOffset);
        } catch (Exception e) {
            log.warn("[Aster] Failed to sync server time, offset=0", e);
        }
    }

    private void loadSymbolFilters() {
        try {
            String json = executePublicGet("/fapi/v1/exchangeInfo", null);
            if (json == null) return;

            JsonNode symbols = objectMapper.readTree(json).path("symbols");
            for (JsonNode s : symbols) {
                String sym = s.path("symbol").asText();
                JsonNode filters = s.path("filters");

                SymbolFilter filter = new SymbolFilter();
                for (JsonNode f : filters) {
                    String type = f.path("filterType").asText();
                    if ("LOT_SIZE".equals(type)) {
                        filter.setStepSize(f.path("stepSize").asText());
                        filter.setMinQty(f.path("minQty").asText());
                    } else if ("MIN_NOTIONAL".equals(type)) {
                        filter.setMinNotional(f.path("minNotional").asText());
                    }
                }
                if (filter.getStepSize() != null) {
                    symbolFilters.put(sym, filter);
                    log.debug("[Aster] Filter for {} loaded", sym);
                }
            }
            log.info("[Aster] Loaded {} symbols from exchangeInfo", symbolFilters.size());
        } catch (Exception e) {
            log.error("[Aster] Error loading exchangeInfo", e);
        }
    }

    /**
     * Executions with signed requests
     */
    private String executePublicGet(String endpoint, String queryParams) {
        try {
            URI uri = new URIBuilder(baseUrl + endpoint)
                    .setCustomQuery(queryParams)
                    .build();

            HttpGet get = new HttpGet(uri);
            log.info("[Aster Public GET] {}", uri);

            try (CloseableHttpResponse resp = httpClient.execute(get)) {
                int code = resp.getCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                if (code == 200) return body;
                log.error("[Aster] Public GET error: code={}, body={}", code, body);
                return null;
            }
        } catch (Exception e) {
            log.error("[Aster] Public GET error {}", endpoint, e);
            return null;
        }
    }

    // Generate signature for the query
    private String sign(String queryString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(privateApiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(hash).toLowerCase();
        } catch (Exception e) {
            log.error("[Aster] Signature generation error HMAC-SHA256", e);
            throw new RuntimeException("[Aster] Signature generation error", e);
        }
    }

    // Adding signature to the request and executing, returns plain body
    private String executeSignedRequest(String method, String endpoint, String queryParams) {
        try {
            long timestamp = System.currentTimeMillis() + serverTimeOffset;
            String query = (queryParams != null && !queryParams.isEmpty())
                    ? queryParams + "&recvWindow=10000&timestamp=" + timestamp
                    : "recvWindow=10000&timestamp=" + timestamp;

            String signature = sign(query);
            String fullQuery = query + "&signature=" + signature;

            URI uri = new URIBuilder(baseUrl + endpoint).setCustomQuery(fullQuery).build();

            if ("GET".equalsIgnoreCase(method)) {
                HttpGet req = new HttpGet(uri);
                req.setHeader("X-MBX-APIKEY", publicApiKey);
                log.info("[Aster] {}", uri);
                try (CloseableHttpResponse resp = httpClient.execute(req)) {
                    return handleResponse(resp);
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                HttpPost req = new HttpPost(uri);
                req.setHeader("X-MBX-APIKEY", publicApiKey);
                req.setHeader("Content-Type", "application/x-www-form-urlencoded");
                log.info("[Aster] {}", uri);
                try (CloseableHttpResponse resp = httpClient.execute(req)) {
                    return handleResponse(resp);
                }
            } else if ("DELETE".equalsIgnoreCase(method)) {
                HttpDelete req = new HttpDelete(uri);
                req.setHeader("X-MBX-APIKEY", publicApiKey);
                log.info("[Aster] DELETE {}", uri);
                try (CloseableHttpResponse resp = httpClient.execute(req)) {
                    return handleResponse(resp);
                }
            }
            throw new IllegalArgumentException("Unsupported method: " + method);
        } catch (Exception e) {
            log.error("[Aster] Error {} request {}", method, endpoint, e);
            return null;
        }
    }

    public List<AsterFundingResponse> getFundingList() {
        try {
            String response = executePublicGet("/fapi/v1/premiumIndex", null);

            if (response == null || response.isEmpty()) {
                log.info("[Aster] Response for funding rates lists is empty");
                return Collections.emptyList();
            }

            List<AsterFundingResponse> fundingsList = objectMapper.readValue(
                    response,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AsterFundingResponse.class)
            );

            return fundingsList.stream()
                    .filter(Objects::nonNull)
                    .filter(s -> s.getSymbol() != null && s.getSymbol().endsWith("USDT"))
                    .peek(s -> s.setSymbol(s.getSymbol().substring(0, s.getSymbol().length() - 4)))
                    .toList();

        } catch (Exception e) {
            log.error("[AsterClient] getFundingList error", e);
            return Collections.emptyList();
        }
    }

    /**
     * Leverage
     */
    public boolean setLeverage(String symbol, int leverage) {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&leverage=").append(leverage);

        String response = executeSignedRequest("POST", "/fapi/v1/leverage", params.toString());

        if (Objects.isNull(response)) {
            return false;  // -2030 or other non-success
        }

        log.info("[Aster] setLeverage response for {}: {}", symbol, response);
        return true;
    }

    public int getMaxLeverage(String symbol) {
        try {
            log.info("[Aster] Getting max leverage for {}", symbol);

            String queryParams = "symbol=" + symbol;
            String response = executeSignedRequest("GET", "/fapi/v1/leverageBracket", queryParams);

            if (response == null || response.isEmpty()) {
                log.warn("[Aster] Empty response for leverage info, using default 10");
                return 10;
            }

            log.debug("[Aster] Leverage response: {}", response);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> responseList = objectMapper.readValue(
                    response,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );

            if (responseList == null || responseList.isEmpty()) {
                log.warn("[Aster] Empty leverage brackets list for {}, using default 10", symbol);
                return 10;
            }

            Map<String, Object> data = responseList.getFirst();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> brackets = (List<Map<String, Object>>) data.get("brackets");

            if (brackets != null && !brackets.isEmpty()) {
                Map<String, Object> firstBracket = brackets.get(0);
                Integer maxLeverage = (Integer) firstBracket.get("initialLeverage");

                log.info("[Aster] Max leverage for {}: {}x", symbol, maxLeverage);
                return maxLeverage;
            }

            log.warn("[Aster] No brackets found for {}, using default 10", symbol);
            return 10;

        } catch (Exception e) {
            log.error("[Aster] Failed to get max leverage for {}, using default 10", symbol, e);
            return 10;
        }
    }

    /**
     * Position open\close\get
     */

    public String openMarketOrder(String symbol, String side, double quantity, String positionSide) {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&side=").append(side.toUpperCase());
        params.append("&type=MARKET");
        params.append("&quantity=").append(quantity);
        params.append("&positionSide=").append(positionSide.toUpperCase());
        params.append("&newOrderRespType=RESULT");

        log.info("[Aster] Market params appended: {}", params);

        try {
            String responseBody = executeSignedRequest("POST", "/fapi/v1/order", params.toString());

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("[Aster] Empty response body (order might be filled instantly)");
                return "aster-" + System.currentTimeMillis();
            }

            OrderResponse response = objectMapper.readValue(responseBody, OrderResponse.class);

            String orderId = null;
            if (response.getOrderId() != null) {
                orderId = String.valueOf(response.getOrderId());
            } else if (response.getClientOrderId() != null && !response.getClientOrderId().isBlank()) {
                orderId = response.getClientOrderId();
            }

            if (orderId == null) {
                log.warn("[Aster] No orderId in response: {}", responseBody);
                return "aster-" + System.currentTimeMillis();
            }

            log.info("[Aster] Market order: orderId={}, status={}, executed={}/{}, avgPrice={}",
                    orderId,
                    response.getStatus(),
                    response.getExecutedQty(),
                    response.getOrigQty(),
                    response.getAvgPrice());

            if ("FILLED".equals(response.getStatus()) || "PARTIALLY_FILLED".equals(response.getStatus())) {
                log.info("[Aster] Order execution confirmed");
            } else if ("NEW".equals(response.getStatus())) {
                log.warn("[Aster] Order placed but not filled yet (status=NEW)");
            } else {
                log.warn("[Aster] Unexpected order status: {}", response.getStatus());
            }

            return orderId;

        } catch (RuntimeException e) {
            log.error("[Aster] Market order API error", e);
            return null;
        } catch (Exception e) {
            log.error("[Aster] Failed to parse order response", e);
            return null;
        }
    }

    // Main method - opening with fixed margin and chosen leverage + side
    public String openPositionWithFixedMargin(String symbol, double usdMargin, int leverage, String direction) {
        final String side;
        final String positionSide;

        if (direction.equalsIgnoreCase("LONG")) {
            side = "BUY";
            positionSide = "LONG";
        } else if (direction.equalsIgnoreCase("SHORT")) {
            side = "SELL";
            positionSide = "SHORT";
        } else {
            log.error("[Aster] Invalid direction: {}", direction);
            return null;
        }

        try {
            double available = getBalance();
            if (available < usdMargin) {
                log.warn("[Aster] Insufficient balance: ${} < ${}", available, usdMargin);
                return null;
            }

            SymbolFilter filter = symbolFilters.get(symbol);
            if (filter == null || filter.getStepSize() == null || filter.getStepSize().isEmpty()) {
                log.warn("[Aster] Empty stepSize for {}", symbol);
                return null;
            }

            double step;
            try {
                step = Double.parseDouble(filter.getStepSize());
            } catch (NumberFormatException e) {
                log.error("[Aster] Invalid stepSize '{}' for {}", filter.getStepSize(), symbol);
                return null;
            }

            double minQty = 0.0;
            if (filter.getMinQty() != null && !filter.getMinQty().isEmpty()) {
                minQty = Double.parseDouble(filter.getMinQty());
            }

            double minNotional = 5.0;
            if (filter.getMinNotional() != null && !filter.getMinNotional().isEmpty()) {
                minNotional = Double.parseDouble(filter.getMinNotional());
            }

            double price = getMarkPrice(symbol);
            if (price <= 0) {
                log.error("[Aster] Failed to get mark price for {}", symbol);
                return null;
            }

            double notional = usdMargin * leverage;

            if (notional < minNotional) {
                log.warn("[Aster] Notional ${} < minNotional ${}, increase margin or leverage",
                        notional, minNotional);
                return null;
            }

            double qty = notional / price;
            double qtyRounded = Math.floor(qty / step) * step;

            if (qtyRounded < minQty) {
                if (qty >= minQty) {
                    qtyRounded = minQty;
                } else {
                    log.warn("[Aster] Rounded quantity {} < minQty {}", qtyRounded, minQty);
                    return null;
                }
            }

            String quantityStr = String.format(Locale.US, "%." + getPrecision(step) + "f", qtyRounded);

            log.info("[Aster] Opening {}: margin ${}, ×{}, qty={}, notional ${}, price ~{}",
                    direction, usdMargin, leverage, quantityStr, notional, price);

            setLeverage(symbol, leverage);

            String orderId = openMarketOrder(symbol, side, Double.parseDouble(quantityStr), positionSide);

            if (orderId == null) {
                log.error("[Aster] Failed to open market order");
                return null;
            }

            if (orderId.startsWith("aster-")) {
                log.warn("[Aster] Using fallback orderId: {}", orderId);
            }

            log.info("[Aster] Position opened: orderId={}", orderId);
            return orderId;

        } catch (Exception e) {
            log.error("[Aster] Error opening position for ${} with ×{}", usdMargin, leverage, e);
            return null;
        }
    }

    public String openPositionWithSize(String symbol, double size, String direction) {
        final String side;
        final String positionSide;

        if (direction.equalsIgnoreCase("LONG")) {
            side = "BUY";
            positionSide = "LONG";
        } else if (direction.equalsIgnoreCase("SHORT")) {
            side = "SELL";
            positionSide = "SHORT";
        } else {
            log.error("[Aster] Invalid direction: {}", direction);
            return null;
        }

        try {
            log.info("[Aster] Opening position by size: symbol={}, size={}, direction={}",
                    symbol, String.format("%.4f", size), direction);

            if (size <= 0) {
                log.error("[Aster] Invalid size: {}", size);
                return null;
            }

            SymbolFilter filter = symbolFilters.get(symbol);
            if (filter == null || filter.getStepSize() == null || filter.getStepSize().isEmpty()) {
                log.warn("[Aster] Empty stepSize for {}, loading...", symbol);
                loadSymbolFilters();
                filter = symbolFilters.get(symbol);

                if (filter == null) {
                    log.error("[Aster] Failed to load filters for {}", symbol);
                    return null;
                }
            }

            double step;
            try {
                step = Double.parseDouble(filter.getStepSize());
            } catch (NumberFormatException e) {
                log.error("[Aster] Invalid stepSize '{}' for {}", filter.getStepSize(), symbol);
                return null;
            }

            double minQty = 0.0;
            if (filter.getMinQty() != null && !filter.getMinQty().isEmpty()) {
                minQty = Double.parseDouble(filter.getMinQty());
            }

            double minNotional = 5.0;
            if (filter.getMinNotional() != null && !filter.getMinNotional().isEmpty()) {
                minNotional = Double.parseDouble(filter.getMinNotional());
            }

            double qtyRounded = Math.floor(size / step) * step;

            if (qtyRounded < minQty) {
                if (size >= minQty) {
                    qtyRounded = minQty;
                    log.warn("[Aster] Adjusted size to minQty: {} → {}",
                            String.format("%.4f", size),
                            String.format("%.4f", qtyRounded));
                } else {
                    log.error("[Aster] Size {} < minQty {}",
                            String.format("%.4f", size),
                            String.format("%.4f", minQty));
                    return null;
                }
            }

            double price = getMarkPrice(symbol);
            if (price <= 0) {
                log.error("[Aster] Failed to get mark price for {}", symbol);
                return null;
            }

            double notional = qtyRounded * price;

            if (notional < minNotional) {
                log.error("[Aster] Notional ${} < minNotional ${}",
                        String.format("%.2f", notional),
                        String.format("%.2f", minNotional));
                return null;
            }

            String quantityStr = String.format(Locale.US, "%." + getPrecision(step) + "f", qtyRounded);

            log.info("[Aster] Position params: size={}, price=${}, notional=${}, step_size={}",
                    quantityStr,
                    String.format("%.6f", price),
                    String.format("%.2f", notional),
                    step);

            String orderId = openMarketOrder(symbol, side, Double.parseDouble(quantityStr), positionSide);

            if (orderId == null) {
                log.error("[Aster] Failed to open market order");
                return null;
            }

            log.info("[Aster] Position opened: orderId={}, size={} {}",
                    orderId,
                    quantityStr,
                    symbol.replace("USDT", ""));

            return orderId;

        } catch (Exception e) {
            log.error("[Aster] Error opening position by size for {}", symbol, e);
            return null;
        }
    }

    public List<AsterPosition> getPositions(String symbol) {
        String query = symbol != null ? "symbol=" + symbol : null;
        String json = executeSignedRequest("GET", "/fapi/v1/positionRisk", query);
        if (json == null) return List.of();

        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, AsterPosition.class));
        } catch (Exception e) {
            log.error("[Aster] Error parsing positions", e);
            return List.of();
        }
    }

    public OrderResult closePosition(String symbol) {
        try {
            List<AsterPosition> positions = getPositions(symbol);

            if (Objects.isNull(positions) || positions.isEmpty()) {
                log.info("[Aster] No positions for {}", symbol);
                return OrderResult.builder()
                        .exchange(ExchangeType.ASTER)
                        .symbol(symbol)
                        .success(true)
                        .message("Position already closed")
                        .build();
            }

            AsterPosition targetPos = null;
            for (AsterPosition pos : positions) {
                double amt = Double.parseDouble(pos.getPositionAmt());
                if (Math.abs(amt) > 1e-8) {
                    targetPos = pos;
                    break;
                }
            }

            if (targetPos == null) {
                log.info("[Aster] All positions for {} empty", symbol);
                return OrderResult.builder()
                        .exchange(ExchangeType.ASTER)
                        .symbol(symbol)
                        .success(true)
                        .message("Position already closed")
                        .build();
            }

            String positionSide = targetPos.getPositionSide();
            double amt = Double.parseDouble(targetPos.getPositionAmt());
            String closeSide = amt > 0 ? "SELL" : "BUY";
            String qty = String.format(Locale.US, "%.3f", Math.abs(amt));

            StringBuilder params = new StringBuilder();
            params.append("symbol=").append(symbol);
            params.append("&side=").append(closeSide);
            params.append("&positionSide=").append(positionSide);
            params.append("&type=MARKET");
            params.append("&quantity=").append(qty);

            log.info("[Aster] Closing in Hedge Mode: symbol={}, side={}, positionSide={}, qty={}",
                    symbol, closeSide, positionSide, qty);

            String result = executeSignedRequest("POST", "/fapi/v1/order", params.toString());

            String closeOrderId = null;
            try {
                OrderResponse closeResp = objectMapper.readValue(result, OrderResponse.class);
                closeOrderId = closeResp.getOrderId() != null
                        ? String.valueOf(closeResp.getOrderId())
                        : closeResp.getClientOrderId();
                log.info("[Aster] Close orderId parsed: {}", closeOrderId);
            } catch (Exception e) {
                log.warn("[Aster] Failed to parse closeOrderId from response: {}", result);
                closeOrderId = result;
            }

            log.info("[Aster] Close order sent, validating position closure");

            boolean closed = waitPositionClosed(symbol, positionSide, 10.0);

            if (closed) {
                log.info("[Aster] Position closed: {} {}", symbol, positionSide);
            } else {
                log.warn("[Aster] Position close Timeout");
            }

            return OrderResult.builder()
                    .exchange(ExchangeType.ASTER)
                    .symbol(symbol)
                    .orderId(closeOrderId)
                    .success(true)
                    .message("Position closed successfully")
                    .build();

        } catch (Exception e) {
            log.error("[Aster] Error closing position for {}", symbol, e);
            return OrderResult.builder()
                    .exchange(ExchangeType.ASTER)
                    .symbol(symbol)
                    .success(true)
                    .message("Error closing position")
                    .build();
        }
    }

    public void cancelAllOrders(String symbol) {
        String params = "symbol=" + symbol;

        log.info("[Aster] Cancelling all orders for {}", symbol);

        try {
            String response = executeSignedRequest("DELETE", "/fapi/v1/allOpenOrders", params);
            log.info("[Aster] All orders cancelled for {}: {}", symbol, response);
        } catch (Exception e) {
            log.error("[Aster] Failed to cancel orders for {}", symbol, e);
        }
    }

    /**
     * OrderBook
     */
    public PremiumIndexResponse getPremiumIndexInfo(String symbol) {
        try {
            URIBuilder builder = new URIBuilder(baseUrl + "/fapi/v1/premiumIndex");
            builder.addParameter("symbol", symbol);
            URI uri = builder.build();

            HttpGet get = new HttpGet(uri);
            log.info("[Aster] GET premium index info for {}", symbol);

            try (CloseableHttpResponse resp = httpClient.execute(get)) {
                int code = resp.getCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (code != 200) {
                    log.error("[Aster] Premium index failed: code={}, body={}", code, body);
                    return null;
                }

                PremiumIndexResponse response = objectMapper.readValue(body, PremiumIndexResponse.class);

                log.info("[Aster] Premium index for {}: fundingRate={}%, nextFunding in {} min, markPrice={}",
                        symbol,
                        response.getLastFundingRateAsDouble() * 100,
                        response.getMinutesUntilFunding(),
                        response.getMarkPrice());

                return response;
            }
        } catch (AsterApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AsterClient] Еrror getting premium index for {}", symbol, e);
            throw new AsterApiException(0, null, "Error: " + e.getMessage(), null);
        }
    }

    public long getMinutesUntilFunding(String symbol) {
        PremiumIndexResponse info = getPremiumIndexInfo(symbol);
        if (info == null) {
            log.error("[Aster] Failed to get premium index for {}", symbol);
            return -1;
        }
        return info.getMinutesUntilFunding();
    }

    /**
     * Utils
     */
    public double getMarkPrice(String symbol) {
        try {
            URIBuilder builder = new URIBuilder(baseUrl + "/fapi/v1/ticker/price");
            builder.addParameter("symbol", symbol);
            URI uri = builder.build();

            HttpGet get = new HttpGet(uri);
            log.info("[Aster] GET mark price для {}", symbol);

            try (CloseableHttpResponse resp = httpClient.execute(get)) {
                int code = resp.getCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (code != 200) {
                    log.error("[Aster] Mark price failed: code={}, body={}", code, body);
                    return 0.0;
                }

                JsonNode root = objectMapper.readTree(body);
                double price = root.path("price").asDouble(0.0);
                log.debug("[Aster] Mark price {}: {}", symbol, price);
                return price;
            }
        } catch (Exception e) {
            log.error("[Aster] Ошибка получения mark price для {}", symbol, e);
            return 0.0;
        }
    }

    public Double getBalance() {
        try {
            String json = executeSignedRequest("GET", "/fapi/v2/balance", null);
            if (Objects.isNull(json)) return 0.0;

            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray() || root.isEmpty()) {
                log.warn("[Aster] Balance is empty or not an array");
                return 0.0;
            }

            double usdtAvailable = 0.0;
            for (JsonNode node : root) {
                if ("USDT".equals(node.path("asset").asText())) {
                    usdtAvailable = node.path("availableBalance").asDouble(0.0);
                    log.info("[Aster] Balance available USDT: {}", usdtAvailable);
                    break;
                }
            }
            return usdtAvailable;
        } catch (Exception e) {
            log.error("[Aster] Error getting balance", e);
            return 0.0;
        }
    }

    private int getPrecision(double step) {
        String s = String.valueOf(step);
        return s.length() - s.indexOf('.') - 1;
    }

    private boolean waitPositionClosed(String symbol, String positionSide, double timeoutSec) {
        long start = System.currentTimeMillis();
        long pollInterval = 300;

        while ((System.currentTimeMillis() - start) / 1000.0 < timeoutSec) {
            try {
                List<AsterPosition> positions = getPositions(symbol);

                if (positions == null || positions.isEmpty()) {
                    log.info("[Aster] waitPositionClosed: {} disappeared → CLOSED", symbol);
                    return true;
                }

                boolean found = false;
                for (AsterPosition p : positions) {
                    if (p.getPositionSide().equals(positionSide)) {
                        double amt = Math.abs(Double.parseDouble(p.getPositionAmt()));

                        if (amt < 0.001) {
                            log.info("[Aster] waitPositionClosed: {} {} size={} → Closed",
                                    symbol, positionSide, amt);
                            return true;
                        }

                        log.debug("[Aster] waitPositionClosed: {} {} size={} still open",
                                symbol, positionSide, amt);
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    log.info("[Aster] waitPositionClosed: {} {} not found → Closed", symbol, positionSide);
                    return true;
                }

                Thread.sleep(pollInterval);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("[Aster] waitPositionClosed exception: {}", e.getMessage());
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        log.warn("[Aster] waitPositionClosed timeout after {}s", timeoutSec);
        return false;
    }

    private String handleResponse(CloseableHttpResponse resp) throws Exception {
        int code = resp.getCode();
        String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
        if (code >= 200 && code < 300) {
            return body;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            int errorCode = root.path("code").asInt(code);
            String msg = root.path("msg").asText();

            if (code == 400 && errorCode == -2030) {
                log.warn("[Aster] Bracket limit for leverage: {}", msg);
                return null;
            }
        } catch (Exception ignored) {
        }

        log.error("[Aster] API Error: code={}, body={}", code, body);
        throw new RuntimeException("[Aster] API error: " + code + " → " + body);
    }

    public AsterBookTicker getBookTicker(String symbol) {
        try {
            URIBuilder builder = new URIBuilder(baseUrl + "/fapi/v1/ticker/bookTicker");
            builder.addParameter("symbol", symbol);
            URI uri = builder.build();

            HttpGet get = new HttpGet(uri);
            log.debug("[Aster] GET book ticker for {}", symbol);

            try (CloseableHttpResponse resp = httpClient.execute(get)) {
                int code = resp.getCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (code != 200) {
                    log.error("[Aster] Book ticker failed: code={}, body={}", code, body);
                    return null;
                }

                AsterBookTicker ticker = objectMapper.readValue(body, AsterBookTicker.class);

                log.info("[Aster] Book ticker {}: bid={} ({}), ask={} ({}), spread={}%",
                        symbol,
                        ticker.getBidPrice(),
                        ticker.getBidQty(),
                        ticker.getAskPrice(),
                        ticker.getAskQty(),
                        calculateSpread(ticker)
                );

                return ticker;
            }
        } catch (Exception e) {
            log.error("[Aster] Error getting book ticker for {}", symbol, e);
            return null;
        }
    }

    private String calculateSpread(AsterBookTicker ticker) {
        try {
            double bid = Double.parseDouble(ticker.getBidPrice());
            double ask = Double.parseDouble(ticker.getAskPrice());
            double spread = ((ask - bid) / ask) * 100;
            return String.format("%.3f", spread);
        } catch (Exception e) {
            return "N/A";
        }
    }

    public Double estimateExecutionPrice(String symbol, double size, boolean isSell) {
        try {
            AsterBookTicker ticker = getBookTicker(symbol);

            if (ticker == null) {
                log.warn("[Aster] Book ticker unavailable for {}", symbol);
                return null;
            }

            double price = isSell
                    ? Double.parseDouble(ticker.getBidPrice())
                    : Double.parseDouble(ticker.getAskPrice());

            double availableQty = isSell
                    ? Double.parseDouble(ticker.getBidQty())
                    : Double.parseDouble(ticker.getAskQty());

            log.info("[Aster] Execution estimate {}: size={}, price={}, available_qty={}, sufficient={}",
                    symbol,
                    String.format("%.4f", size),
                    String.format("%.6f", price),
                    String.format("%.4f", availableQty),
                    size <= availableQty
            );

            if (size > availableQty) {
                double penalty = isSell ? 0.998 : 1.002;
                price = price * penalty;
                log.warn("[Aster] Insufficient liquidity at best price, applying {}% penalty",
                        (Math.abs(1 - penalty) * 100));
            }

            return price;

        } catch (Exception e) {
            log.error("[Aster] Error estimating execution price for {}", symbol, e);
            return null;
        }
    }

    public Double calculateMaxSizeForMargin(String symbol, double marginUsd, int leverage, boolean isBuy) {
        try {
            double notional = marginUsd * leverage;

            Double executionPrice = estimateExecutionPrice(symbol, notional / 1000.0, !isBuy);

            if (executionPrice == null) {
                executionPrice = getMarkPrice(symbol);
                if (executionPrice <= 0) {
                    log.error("[Aster] Failed to get price for {}", symbol);
                    return null;
                }
                log.warn("[Aster] Using mark price as fallback: ${}", String.format("%.6f", executionPrice));
            }

            double maxSize = notional / executionPrice;

            SymbolFilter filter = symbolFilters.get(symbol);
            if (filter != null && filter.getStepSize() != null && !filter.getStepSize().isEmpty()) {
                try {
                    BigDecimal bdSize = new BigDecimal(String.valueOf(maxSize));
                    BigDecimal bdStep = new BigDecimal(filter.getStepSize());
                    maxSize = bdSize
                            .divide(bdStep, 0, RoundingMode.FLOOR)
                            .multiply(bdStep)
                            .doubleValue();
                } catch (Exception e) {
                    log.warn("[Aster] Failed to apply stepSize for {}: {}", symbol, e.getMessage());
                }
            }

            log.info("[Aster] Max size for margin ${} @ {}x: {} {} (price: ${})",
                    String.format("%.2f", marginUsd),
                    leverage,
                    String.format("%.4f", maxSize),
                    symbol.replace("USDT", ""),
                    String.format("%.6f", executionPrice));

            return maxSize;

        } catch (Exception e) {
            log.error("[Aster] Error calculating max size for {}", symbol, e);
            return null;
        }
    }

    /**
     * Sl\Tp
     */

    public String placeStopLoss(String symbol, String side, String positionSide, double stopPrice) {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&side=").append(side);
        params.append("&positionSide=").append(positionSide);
        params.append("&type=STOP_MARKET");
        params.append("&stopPrice=").append(stopPrice);
        params.append("&closePosition=true");
        params.append("&workingType=MARK_PRICE");
        params.append("&priceProtect=TRUE");
        params.append("&newOrderRespType=RESULT");

        log.info("[Aster] Placing Stop Loss: {} {} stopPrice={}", symbol, positionSide, stopPrice);

        try {
            String responseBody = executeSignedRequest("POST", "/fapi/v1/order", params.toString());

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("[Aster] Empty SL response");
                return null;
            }

            OrderResponse response = objectMapper.readValue(responseBody, OrderResponse.class);
            String orderId = response.getOrderId() != null
                    ? String.valueOf(response.getOrderId())
                    : response.getClientOrderId();

            log.info("[Aster] Stop Loss placed: orderId={}, status={}", orderId, response.getStatus());
            return orderId;

        } catch (Exception e) {
            log.error("[Aster] Failed to place Stop Loss for {}", symbol, e);
            return null;
        }
    }

    public String placeTakeProfit(String symbol, String side, String positionSide, double tpPrice) {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&side=").append(side);
        params.append("&positionSide=").append(positionSide);
        params.append("&type=TAKE_PROFIT_MARKET");
        params.append("&stopPrice=").append(tpPrice);
        params.append("&closePosition=true");
        params.append("&workingType=MARK_PRICE");
        params.append("&priceProtect=TRUE");
        params.append("&newOrderRespType=RESULT");

        log.info("[Aster] Placing Take Profit: {} {} stopPrice={}", symbol, positionSide, tpPrice);

        try {
            String responseBody = executeSignedRequest("POST", "/fapi/v1/order", params.toString());

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("[Aster] Empty TP response");
                return null;
            }

            OrderResponse response = objectMapper.readValue(responseBody, OrderResponse.class);
            String orderId = response.getOrderId() != null
                    ? String.valueOf(response.getOrderId())
                    : response.getClientOrderId();

            log.info("[Aster] Take Profit placed: orderId={}, status={}", orderId, response.getStatus());
            return orderId;

        } catch (Exception e) {
            log.error("[Aster] Failed to place Take Profit for {}", symbol, e);
            return null;
        }
    }

    public AsterTrade getTradeResultByOrderId(String symbol, Long orderId) {
        try {
            String queryParams = "symbol=" + symbol + "&orderId=" + orderId + "&limit=100";
            String json = executeSignedRequest("GET", "/fapi/v1/userTrades", queryParams);

            if (json == null || json.isBlank()) {
                log.warn("[Aster] getTradeResult: empty response for symbol={}, orderId={}", symbol, orderId);
                return null;
            }

            List<AsterTrade> trades = objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AsterTrade.class)
            );

            if (trades == null || trades.isEmpty()) {
                log.info("[Aster] getTradeResult: no trades found for symbol={}, orderId={}", symbol, orderId);
                return null;
            }

            List<AsterTrade> orderTrades = trades.stream()
                    .filter(t -> orderId.equals(t.getOrderId()))
                    .toList();

            if (orderTrades.isEmpty()) {
                log.info("[Aster] getTradeResult: no trades matched orderId={}", orderId);
                return null;
            }

            // Суммируем по всем трейдам ордера (ордер мог исполниться частями)
            double totalPnl = orderTrades.stream()
                    .mapToDouble(t -> {
                        try {
                            return Double.parseDouble(t.getRealizedPnl());
                        } catch (Exception e) {
                            return 0.0;
                        }
                    }).sum();

            double totalCommission = orderTrades.stream()
                    .mapToDouble(t -> {
                        try {
                            return Double.parseDouble(t.getCommission());
                        } catch (Exception e) {
                            return 0.0;
                        }
                    }).sum();

            double totalQty = orderTrades.stream()
                    .mapToDouble(t -> {
                        try {
                            return Double.parseDouble(t.getQty());
                        } catch (Exception e) {
                            return 0.0;
                        }
                    }).sum();

            double totalQuoteQty = orderTrades.stream()
                    .mapToDouble(t -> {
                        try {
                            return Double.parseDouble(t.getQuoteQty());
                        } catch (Exception e) {
                            return 0.0;
                        }
                    }).sum();

            double avgPrice = totalQty > 0 ? totalQuoteQty / totalQty : 0.0;

            AsterTrade last = orderTrades.getLast();

            AsterTrade result = new AsterTrade();
            result.setOrderId(orderId);
            result.setSymbol(symbol);
            result.setSide(last.getSide());
            result.setPositionSide(last.getPositionSide());
            result.setPrice(avgPrice);
            result.setQty(String.valueOf(totalQty));
            result.setQuoteQty(String.valueOf(totalQuoteQty));
            result.setCommission(String.valueOf(totalCommission));
            result.setCommissionAsset(last.getCommissionAsset());
            result.setRealizedPnl(String.valueOf(totalPnl));
            result.setTime(last.getTime());
            result.setMaker(last.getMaker());
            result.setBuyer(last.getBuyer());

            log.info("[Aster] getTradeResult: symbol={}, orderId={}, trades={}, avgPrice={}, qty={}, quoteQty={}, commission={}, realizedPnl={}",
                    symbol, orderId, orderTrades.size(), avgPrice, totalQty, totalQuoteQty, totalCommission, totalPnl);

            return result;

        } catch (Exception e) {
            log.error("[Aster] getTradeResult error for symbol={}, orderId={}", symbol, orderId, e);
            return null;
        }
    }

    public double getAccumulatedFundingFee(String symbol, long openedAt) {
        try {
            String queryParams = "symbol=" + symbol
                    + "&incomeType=FUNDING_FEE"
                    + "&startTime=" + openedAt
                    + "&limit=100";

            String json = executeSignedRequest("GET", "/fapi/v1/income", queryParams);
            if (json == null || json.isBlank()) {
                log.warn("[Aster] getAccumulatedFundingFee: empty response for {}", symbol);
                return 0.0;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> incomes = objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            if (incomes == null || incomes.isEmpty()) {
                log.info("[Aster] getAccumulatedFundingFee: no funding entries for {} since {}", symbol, openedAt);
                return 0.0;
            }

            double total = incomes.stream()
                    .mapToDouble(e -> {
                        try {
                            return Double.parseDouble((String) e.get("income"));
                        } catch (Exception ex) {
                            return 0.0;
                        }
                    })
                    .sum();

            log.info("[Aster] Accumulated funding fee: symbol={}, entries={}, total={}, since={}",
                    symbol, incomes.size(), String.format("%.6f", total), openedAt);

            return total;

        } catch (Exception e) {
            log.error("[Aster] getAccumulatedFundingFee error for {}: {}", symbol, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Exceptions
     */
    private String validateResponse(CloseableHttpResponse resp) throws IOException, ParseException {
        int status = resp.getCode();
        String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

        if (status >= 200 && status < 300) {
            return body;
        }

        AsterErrorResponse error = tryParseAsterError(body);

        if (status == 429) {
            throw new AsterRateLimitException(status, Objects.nonNull(error) ? error.getCode() : null,
                    Objects.nonNull(error) ? error.getMsg() : "Rate limit exceeded", body);
        }

        if (status == 418) {
            throw new AsterIpBanException(status, Objects.nonNull(error) ? error.getCode() : null,
                    Objects.nonNull(error) ? error.getMsg() : "IP banned", body);
        }

        if (status == 503) {
            throw new AsterUnknownExecutionException(status, Objects.nonNull(error) ? error.getCode() : null,
                    Objects.nonNull(error) ? error.getMsg() : "Execution status unknown", body);
        }

        if (status >= 400 && status < 500) {
            throw new AsterApiException(status, Objects.nonNull(error) ? error.getCode() : null,
                    Objects.nonNull(error) ? error.getMsg() : "Client error", body);
        }

        if (status >= 500) {
            throw new AsterApiException(status, Objects.nonNull(error) ? error.getCode() : null,
                    Objects.nonNull(error) ? error.getMsg() : "Server error", body);
        }

        throw new AsterApiException(status, Objects.nonNull(error) ? error.getCode() : null,
                Objects.nonNull(error) ? error.getMsg() : "Unexpected HTTP status", body);
    }

    private AsterErrorResponse tryParseAsterError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(body, AsterErrorResponse.class);
        } catch (Exception e) {
            return null;
        }
    }
}
