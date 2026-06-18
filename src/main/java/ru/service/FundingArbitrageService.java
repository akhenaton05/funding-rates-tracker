package ru.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import ru.client.aster.AsterClient;
import ru.client.hyperliquid.HyperliquidClient;
import ru.client.lighter.LighterClient;
import ru.config.FundingConfig;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangePosition;
import ru.dto.exchanges.ExchangeType;
import ru.dto.funding.FundingApiResponseDto;
import ru.dto.funding.FundingOpenSignal;
import ru.dto.funding.ArbitrageRates;
import ru.dto.funding.HoldingMode;
import ru.dto.funding.aster.AsterFundingResponse;
import ru.event.FundingAlertEvent;
import ru.event.NewArbitrageEvent;
import ru.utils.FundingApiParser;
import ru.utils.FundingArbitrageContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FundingArbitrageService {

    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private final FundingArbitrageContext fundingContext;
    private final ApplicationEventPublisher eventPublisher;
    private final FundingConfig fundingConfig;
    private final FundingApiParser fundingApiParser;

    private static final Set<String> SUPPORTED_EXCHANGES = Set.of(
            "lighter",
            //"extended",
            "aster",
            "hyperliquid"
    );

    public FundingArbitrageService(CloseableHttpClient httpClient,
                                   FundingArbitrageContext fundingContext,
                                   ApplicationEventPublisher eventPublisher,
                                   FundingConfig fundingConfig, FundingApiParser fundingApiParser) {
        this.fundingConfig = fundingConfig;
        this.fundingApiParser = fundingApiParser;
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.fundingContext = fundingContext;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 54 * * * *")
    private void fundingTracker() {
        try {
            List<ArbitrageRates> arbitrageRates = calculateArbitrageRates();

            if (arbitrageRates.isEmpty()) {
                log.warn("[FundingBot] No arbitrage rates calculated");
                return;
            }

            double minRate = fundingConfig.getThresholds().getSmartModeRate();
            double fastThreshold = fundingConfig.getThresholds().getFastModeRate();

            //Signals with arb rate > min rate
            long validCount = arbitrageRates.stream()
                    .filter(r -> r.getArbitrageRate() >= minRate)
                    .count();
            log.info("[FundingBot] {} signals above min threshold {}bps", validCount, minRate);

            //Sending signals for every filtered events
            for (ArbitrageRates rate : arbitrageRates) {
                double fundingRate = rate.getArbitrageRate();

                if (fundingRate < minRate) {
                    break;
                }

                if (fundingContext.isBlacklisted(rate.getSymbol())) {
                    continue;
                }

                if (rate.getOiRank() != null) {
                    log.info("[FundingBot] Signal: {} | Spread: {}bps | OI Rank: #{}",
                            rate.getSymbol(),
                            String.format("%.2f", fundingRate),
                            rate.getOiRank());
                } else {
                    log.info("[FundingBot] Signal: {} | Spread: {}bps",
                            rate.getSymbol(),
                            String.format("%.2f", fundingRate));
                }

                HoldingMode selectedMode;
                int leverage;

                //Mode selecting
                if (fundingRate >= fastThreshold) {
                    selectedMode = HoldingMode.FAST_MODE;
                    leverage = fundingConfig.getFast().getLeverage();
                    log.info("[FundingBot] FastMode: {} {}bps, leverage {}x",
                            rate.getSymbol(), fundingRate, leverage);
                } else {
                    selectedMode = HoldingMode.SMART_MODE;
                    leverage = fundingConfig.getSmart().getLeverage();
                    log.info("[FundingBot] SmartMode: {} {}bps, leverage {}x",
                            rate.getSymbol(), fundingRate, leverage);
                }

                //Telegram notification
                for (Long chatId : fundingContext.getSubscriberIds()) {
                    eventPublisher.publishEvent(
                            new FundingAlertEvent(chatId, rate, selectedMode, leverage));
                }

                //Open position signal
                FundingOpenSignal signal = convertToSignal(rate, selectedMode, leverage, fundingRate);
                eventPublisher.publishEvent(new NewArbitrageEvent(signal));
            }

        } catch (Exception e) {
            log.error("[FundingBot] Error tracking funding rates", e);
        }
    }



    public List<ArbitrageRates> calculateArbitrageRates() {
        Map<String, Map<String, Object>> fundingRates = fundingApiParser.getFundingRates();

        if (fundingRates.isEmpty()) {
            log.error("[FundingBot] No funding rates available");
            return Collections.emptyList();
        }

        Map<String, Integer> oiRankings = Collections.emptyMap();
        if (fundingConfig.getOi().isEnabled()) {
            //oiRankings = getOiRankings();
            log.info("[FundingBot] OI filter enabled with max rank: {}",
                    fundingConfig.getOi().getMaxRank());
        }

        Map<String, Map<String, Object>> filteredRates = new HashMap<>();

        for (Map.Entry<String, Map<String, Object>> entry : fundingRates.entrySet()) {
            String exchangeName = entry.getKey().toLowerCase();

            if (SUPPORTED_EXCHANGES.contains(exchangeName) && !fundingContext.isExchangeDisabled(exchangeName)) {
                filteredRates.put(entry.getKey(), entry.getValue());
            }
        }

        log.info("[FundingBot] Filtered {} supported exchanges from {} total",
                filteredRates.size(), fundingRates.size());

        if (filteredRates.size() < 2) {
            log.warn("[FundingBot] Not enough supported exchanges for arbitrage: {}",
                    filteredRates.keySet());
            return Collections.emptyList();
        }

        List<ArbitrageRates> arbitrageRates = new ArrayList<>();
        List<String> exchanges = new ArrayList<>(filteredRates.keySet());

        final Map<String, Integer> finalOiRankings = oiRankings;
        int maxRank = fundingConfig.getOi().getMaxRank();

        for (int i = 0; i < exchanges.size(); i++) {
            for (int j = i + 1; j < exchanges.size(); j++) {
                String ex1Name = exchanges.get(i);
                String ex2Name = exchanges.get(j);

                Map<String, Object> ex1Rates = filteredRates.get(ex1Name);
                Map<String, Object> ex2Rates = filteredRates.get(ex2Name);

                arbitrageRates.addAll(
                        findArbitrageOpportunities(ex1Name, ex1Rates, ex2Name, ex2Rates,
                                finalOiRankings, maxRank)
                );
            }
        }

        //Sort by arbitrage rate
        arbitrageRates.sort(Comparator.comparingDouble(ArbitrageRates::getArbitrageRate).reversed());

        return arbitrageRates;
    }

    private List<ArbitrageRates> findArbitrageOpportunities(
            String ex1Name,
            Map<String, Object> ex1Rates,
            String ex2Name,
            Map<String, Object> ex2Rates,
            Map<String, Integer> oiRankings,
            int maxRank) {

        List<ArbitrageRates> opportunities = new ArrayList<>();

        ExchangeType ex1Type = parseExchangeType(ex1Name);
        ExchangeType ex2Type = parseExchangeType(ex2Name);

        if (ex1Type == null || ex2Type == null) {
            log.error("[FundingBot] Failed to parse supported exchange: {} or {}",
                    ex1Name, ex2Name);
            return opportunities;
        }

        //Find common symbols
        for (Map.Entry<String, Object> entry : ex1Rates.entrySet()) {
            String symbol = entry.getKey();

            if (ex2Rates.containsKey(symbol)) {

                //OI filter
                Integer oiRank = null;
                if (fundingConfig.getOi().isEnabled()) {
                    oiRank = oiRankings.get(symbol);

                    if (oiRank == null) {
                        log.debug("[FundingBot] No OI rank for {}, skipping", symbol);
                        continue;
                    }

                    if (oiRank > maxRank) {
                        log.debug("[FundingBot] {} OI rank {} > {}, skipping",
                                symbol, oiRank, maxRank);
                        continue;
                    }
                }

                double ex1Rate = ((Number) entry.getValue()).doubleValue();
                double ex2Rate = ((Number) ex2Rates.get(symbol)).doubleValue();

                double arbitrage = Math.abs(ex1Rate - ex2Rate) * 100; //Normalizing to bps * 100

                String action = buildActionDescription(
                        ex1Name, ex1Rate, ex1Type,
                        ex2Name, ex2Rate, ex2Type
                );

                opportunities.add(ArbitrageRates.builder()
                        .symbol(symbol)
                        .arbitrageRate(arbitrage)
                        .firstExchange(ex1Type)
                        .secondExchange(ex2Type)
                        .firstRate(ex1Rate)
                        .secondRate(ex2Rate)
                        .action(action)
                        .oiRank(oiRank)
                        .build());
            }
        }

        return opportunities;
    }

    private ExchangeType parseExchangeType(String exchangeName) {
        if (exchangeName == null) {
            return null;
        }

        String normalized = exchangeName.toLowerCase();

        return switch (normalized) {
            case "extended" -> ExchangeType.EXTENDED;
            case "aster" -> ExchangeType.ASTER;
            case "lighter" -> ExchangeType.LIGHTER;
            case "hyperliquid" -> ExchangeType.HYPERLIQUID;
            default -> null;
        };
    }

    private String buildActionDescription(
            String ex1Name, double ex1Rate, ExchangeType ex1Type,
            String ex2Name, double ex2Rate, ExchangeType ex2Type) {

        // Lower rate → LONG, Higher rate → SHORT
        if (ex1Rate < ex2Rate) {
            return String.format("LONG %s, SHORT %s", ex1Name, ex2Name);
        } else {
            return String.format("SHORT %s, LONG %s", ex1Name, ex2Name);
        }
    }

    private FundingOpenSignal convertToSignal(
            ArbitrageRates rates,
            HoldingMode mode,
            Integer leverage,
            double rate) {

        Direction firstDir = rates.getFirstDirection();
        Direction secondDir = rates.getSecondDirection();

        //Create positions from ArbitrageRates data
        ExchangePosition first = ExchangePosition.builder()
                .exchange(rates.getFirstExchange())
                .direction(firstDir)
                .build();

        ExchangePosition second = ExchangePosition.builder()
                .exchange(rates.getSecondExchange())
                .direction(secondDir)
                .build();

        return FundingOpenSignal.builder()
                .ticker(rates.getSymbol())
                .firstPosition(first)
                .secondPosition(second)
                .action(rates.getAction())
                .mode(mode)
                .leverage(leverage)
                .rate(rate)
                .build();
    }

//    private Map<String, Map<String, Object>> executeRequest(HttpGet httpGet) throws Exception {
//        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
//            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
//            log.debug("Received response: {}", responseBody);
//            return parseResponse(responseBody);
//        } catch (Exception e) {
//            log.error("Error sending request", e);
//            throw new Exception("Error sending request: " + e.getMessage());
//        }
//    }

//    private Map<String, Map<String, Object>> parseResponse(String responseBody) throws Exception {
//        Map<String, Object> fullResponse = objectMapper.readValue(responseBody, Map.class);
//        log.info("Full response received");
//
//        Map<String, Map<String, Object>> fundingRates =
//                (Map<String, Map<String, Object>>) fullResponse.get("funding_rates");
//
//        if (Objects.isNull(fundingRates)) {
//            throw new ValidationException("Failed to get funding rates");
//        }
//
//        log.info("Funding rates found");
//        return fundingRates;
//    }

//    private Map<String, Object> getFullApiResponse() {
//        long now = System.currentTimeMillis();
//
//        if (cachedFullResponse != null && (now - lastFetchTime) < CACHE_TTL_MS) {
//            log.debug("[FundingBot] Using cached API response");
//            return cachedFullResponse;
//        }
//
//        try {
//            HttpGet httpGet = new HttpGet(API_URL);
//
//            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
//                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
//                log.debug("Received response: {}", responseBody);
//
//                cachedFullResponse = objectMapper.readValue(responseBody, Map.class);
//                lastFetchTime = now;
//
//                log.info("[FundingBot] Full API response received and cached");
//                return cachedFullResponse;
//            }
//
//        } catch (Exception e) {
//            log.error("[FundingBot] Failed to get API response", e);
//            return Collections.emptyMap();
//        }
//    }

//    private Map<String, Integer> getOiRankings() {
//        Map<String, Object> fullResponse = getFullApiResponse();
//
//        if (fullResponse.isEmpty()) {
//            log.warn("[FundingBot] Full response is empty");
//            return Collections.emptyMap();
//        }
//
//        Map<String, Object> oiRankingsRaw = (Map<String, Object>) fullResponse.get("oi_rankings");
//
//        if (oiRankingsRaw == null) {
//            log.warn("[FundingBot] No oi_rankings in API response");
//            return Collections.emptyMap();
//        }
//
//        Map<String, Integer> oiRankings = new HashMap<>();
//
//        for (Map.Entry<String, Object> entry : oiRankingsRaw.entrySet()) {
//            String symbol = entry.getKey();
//            Object rankValue = entry.getValue();
//
//            try {
//                int rank;
//                if (rankValue instanceof String) {
//                    String rankStr = (String) rankValue;
//                    rank = rankStr.contains("+") ? 999 : Integer.parseInt(rankStr);
//                } else {
//                    rank = ((Number) rankValue).intValue();
//                }
//
//                oiRankings.put(symbol, rank);
//
//            } catch (Exception e) {
//                log.debug("[FundingBot] Failed to parse rank for {}: {}", symbol, rankValue);
//            }
//        }
//
//        log.info("[FundingBot] Parsed {} OI rankings", oiRankings.size());
//        return oiRankings;
//    }
}



