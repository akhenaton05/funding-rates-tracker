package ru.utils;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.client.aster.AsterClient;
import ru.client.hyperliquid.HyperliquidClient;
import ru.client.lighter.LighterClient;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.ExchangeType;
import ru.dto.funding.ArbitrageRates;
import ru.dto.funding.FundingHistoryDto;
import ru.exchanges.Exchange;
import ru.exchanges.factory.ExchangeFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Data
@Slf4j
@Component
@AllArgsConstructor
public class FundingHistoryParser {
    private final AsterClient asterClient;
    private final HyperliquidClient hyperliquidClient;
    private final LighterClient lighterClient;
    private List<Exchange> exchanges;
    private final ExchangeFactory exchangeFactory;

    @PostConstruct
    public void initialiseExchanges() {
        this.exchanges = List.of(
                exchangeFactory.getExchange(ExchangeType.ASTER),
                //exchangeFactory.getExchange(ExchangeType.HYPERLIQUID),
                exchangeFactory.getExchange(ExchangeType.LIGHTER)
        );
    }

    public BigDecimal parseFundingHistoryForExchange(ArbitrageRates rates, String symbol, long startTime, long endTime) {
        startTime = endTime - startTime * 24 * 60 * 60 * 1000L; //Days from signature to millis
        long finalStartTime = startTime;

        Map<ExchangeType, List<FundingHistoryDto>> fundingHistoryMap = exchanges.stream()
                .filter(e -> e.getType().equals(rates.getFirstExchange()) || e.getType().equals(rates.getSecondExchange()))
                .collect(Collectors.toMap(Exchange::getType, v -> v.getFundingHistoryForSymbol(symbol, finalStartTime, endTime)));

        return calculateExchangePair(symbol, fundingHistoryMap, rates);
    }

    public BigDecimal calculateExchangePair(String symbol, Map<ExchangeType, List<FundingHistoryDto>> historyMap, ArbitrageRates rates) {

        Map<ExchangeType, BigDecimal> sums = new EnumMap<>(ExchangeType.class);
        historyMap.forEach((exchange, list) -> {
            BigDecimal sum = list.stream()
                    .map(FundingHistoryDto::getFundingRate)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sums.put(exchange, sum);
        });

        Map.Entry<ExchangeType, BigDecimal> minEntry = sums.entrySet().stream()
                .min(Map.Entry.comparingByValue()).orElseThrow();

        Map.Entry<ExchangeType, BigDecimal> maxEntry = sums.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow();

        BigDecimal spread = maxEntry.getValue().subtract(minEntry.getValue());

        ExchangeType shortCalculated = maxEntry.getKey();
        ExchangeType shortCurrent = rates.getFirstDirection().equals(Direction.SHORT) ? rates.getFirstExchange() : rates.getSecondExchange();

        log.info("[FundingHistoryParser] Short calculated: {}; Actual {}", shortCalculated, shortCurrent);

        boolean directionMatches = shortCalculated.equals(shortCurrent);
        BigDecimal signedSpread = directionMatches ? spread : spread.negate();

        log.info("[FundingHistoryParser] {} best pair: SHORT {} + LONG {}, spread={}%",
                symbol,
                maxEntry.getKey(),
                minEntry.getKey(),
                spread);

        return signedSpread;
    }

    public Map<ExchangeType, List<FundingHistoryDto>> parseFundingHistory(String symbol, long startTime, long endTime) {
        startTime = endTime - startTime * 24 * 60 * 60 * 1000L; //Days from signature to millis
        long finalStartTime = startTime;

        Map<ExchangeType, List<FundingHistoryDto>> fundingHistoryMap = exchanges.stream()
                .collect(Collectors.toMap(Exchange::getType, v -> v.getFundingHistoryForSymbol(symbol, finalStartTime, endTime)));

        //log.info("[FundingHistoryMap] Parsed values:{}", fundingHistoryMap);

        BigDecimal spread = calculatePair(symbol, fundingHistoryMap);

        return fundingHistoryMap;
    }

    public BigDecimal calculatePair(String symbol, Map<ExchangeType, List<FundingHistoryDto>> historyMap) {

        Map<ExchangeType, BigDecimal> sums = new EnumMap<>(ExchangeType.class);
        historyMap.forEach((exchange, list) -> {
            BigDecimal sum = list.stream()
                    .map(FundingHistoryDto::getFundingRate)  // базовый класс
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sums.put(exchange, sum);
        });

        log.info("[FundingHistoryParser] Rate sum for {}: Aster={}, Lighter={}, Hyperliquid={}",
                symbol,
                sums.get(ExchangeType.ASTER),
                sums.get(ExchangeType.LIGHTER),
                sums.get(ExchangeType.HYPERLIQUID)
        );

        Map.Entry<ExchangeType, BigDecimal> minEntry = sums.entrySet().stream()
                .min(Map.Entry.comparingByValue()).orElseThrow();

        Map.Entry<ExchangeType, BigDecimal> maxEntry = sums.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow();

        BigDecimal spread = maxEntry.getValue().subtract(minEntry.getValue());

        log.info("[FundingHistoryParser] {} best pair: SHORT {} + LONG {}, spread={}%",
                symbol,
                maxEntry.getKey(),
                minEntry.getKey(),
                spread);

        return spread.multiply(BigDecimal.valueOf(8));
    }
}
