package ru.utils;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.client.aster.AsterClient;
import ru.client.hyperliquid.HyperliquidClient;
import ru.client.lighter.LighterClient;
import ru.dto.funding.FundingApiResponseDto;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
@AllArgsConstructor
public class FundingApiParser {
    private final AsterClient asterClient;
    private final LighterClient lighterClient;
    private final HyperliquidClient hyperliquidClient;

    public Map<String, Map<String, Object>> getFundingRates() {
        Map<String, Map<String, Object>> fundingRates = new HashMap<>();

        fundingRates.put("aster", toRateMap("aster",
                asterClient.getFundingList().stream()
                        .peek(item -> item.setFundingRate(item.getFundingUI()))
                        .peek(item -> item.setFundingRate(normalizeAsterRate(item.getSymbol(), item.getFundingRate())))
                        .toList()));
        fundingRates.put("lighter", toRateMap("lighter",
                lighterClient.getFundingList().stream()
                        .peek(item -> item.setFundingRate(item.getFundingUI()))
                        .toList()));
        fundingRates.put("hyperliquid", toRateMap("hyperliquid",
                hyperliquidClient.getFundingList().stream()
                        .peek(item -> item.setFundingRate(item.getFundingUI()))
                        .toList()));

        log.info("[FundingArbitrageService] Parsed API response: {}", fundingRates);
        return fundingRates;
    }

    private Map<String, Object> toRateMap(String exchange, List<? extends FundingApiResponseDto> list) {
        Map<String, Object> ratesMap = list.stream()
                .filter(Objects::nonNull)
                .filter(s -> s.getFundingRate() != null)
                .filter(s -> s.getSymbol() != null && !s.getSymbol().isBlank())
                .collect(Collectors.toMap(
                        FundingApiResponseDto::getSymbol,
                        s -> normalizeRate(exchange, s.getFundingRate())
                ));

        log.info("[FundingArbitrageService] Rate map collected: {}", ratesMap);
        return ratesMap;
    }

    private BigDecimal normalizeRate(String exchange, BigDecimal uiRatePercent) {
        if (uiRatePercent == null) {
            return null;
        }

        return switch (exchange.toLowerCase()) {
            case "lighter", "hyperliquid" -> uiRatePercent.multiply(BigDecimal.valueOf(8));
            case "aster" -> uiRatePercent;
            default -> uiRatePercent;
        };
    }

    private BigDecimal normalizeAsterRate(String symbol, BigDecimal uiRatePercent) {
        int interval = AsterParser.DEFAULT_ASTER_INTERVALS.getOrDefault(symbol, 8);

        return switch (interval) {
            case 1 -> uiRatePercent.multiply(BigDecimal.valueOf(8));
            case 4 -> uiRatePercent.multiply(BigDecimal.valueOf(2));
            case 8 -> uiRatePercent;
            default -> uiRatePercent;
        };
    }
}
