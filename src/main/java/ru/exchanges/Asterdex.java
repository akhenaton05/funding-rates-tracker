package ru.exchanges;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import ru.client.aster.AsterClient;
import ru.dto.exchanges.*;
import ru.dto.exchanges.aster.*;
import ru.dto.funding.FundingCloseSignal;
import ru.dto.funding.FundingHistoryDto;
import ru.dto.funding.aster.AsterFundingHistoryDto;
import ru.exceptions.ClosingPositionException;
import ru.exceptions.OpeningPositionException;
import ru.exceptions.aster.AsterApiException;
import ru.mapper.aster.AsterOrderBookMapper;
import ru.mapper.aster.AsterPositionMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class Asterdex implements Exchange {

    private final AsterClient asterClient;
    private final double makerFee = 0.00005;  // 0.005%
    private final double takerFee = 0.0004;   // 0.04%

    private ExchangeType currentPairedExchange = null;

    @Override
    public void setPairedExchange(ExchangeType pairedWith) {
        this.currentPairedExchange = pairedWith;
    }

    @Override
    public int getOpenDelay(ExchangeType pairedWith) {
        return switch (pairedWith) {
            case EXTENDED -> 3000;  // Wait 3s for Extended to open
            case LIGHTER -> 4000;
            case HYPERLIQUID -> 2000;
            default -> 0;
        };
    }

    @Override
    public int getCloseDelay(ExchangeType pairedWith) {
        return switch (pairedWith) {
            case EXTENDED -> 3000;  // Wait 3s for Extended to close
            case LIGHTER -> 1000;   // Wait 1s for Lighter to close
            case HYPERLIQUID -> 2000;
            default -> 0;
        };
    }

    @Override
    public ExchangeType getType() {
        return ExchangeType.ASTER;
    }

    @Override
    public String getName() {
        return "Aster";
    }

    @Override
    public String formatSymbol(String ticker) {
        return ticker + "USDT";
    }

    @Override
    public Balance getBalance() {
        double balance = asterClient.getBalance();

        return Balance.builder()
                .balance(balance)
                .margin(balance * 0.85) //85% of balance for slippage and fees
                .build();
    }

    @Override
    public OrderBook getOrderBook(String symbol) {
        AsterBookTicker astOrderBook = asterClient.getBookTicker(formatSymbol(symbol));
        return AsterOrderBookMapper.toOrderBook(astOrderBook, symbol);
    }

    @Override
    public List<Position> getPositions(String ticker, Direction side) {
        try {
            String symbol = formatSymbol(ticker);

            log.debug("[Aster] Getting positions: {} {}", symbol, side);

            // Get raw positions from client (returns BOTH, LONG, SHORT in Hedge mode)
            List<AsterPosition> rawPositions = asterClient.getPositions(symbol);

            if (rawPositions == null || rawPositions.isEmpty()) {
                log.debug("[Aster] No positions found for {}", symbol);
                return List.of();
            }

            //Filter by positionSide (LONG/SHORT) + map to Position
            String targetSide = side.toString();  // "LONG" or "SHORT"

            List<Position> positions = rawPositions.stream()
                    .filter(p -> {
                        //Match only requested side
                        boolean matches = p.getPositionSide().equalsIgnoreCase(targetSide);

                        if (!matches) {
                            log.trace("[Aster] Skipping position: {} (looking for {})",
                                    p.getPositionSide(), targetSide);
                        }

                        return matches;
                    })
                    .map(AsterPositionMapper::toPosition)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.debug("[Aster] Found {} {} positions for {}",
                    positions.size(), targetSide, symbol);

            return positions;

        } catch (Exception e) {
            log.error("[Aster] Error getting positions for {} {}: {}",
                    ticker, side, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public OrderResult closePosition(String symbol, Direction currentSide) {
        try {
            if (currentPairedExchange != null) {
                int delay = getCloseDelay(currentPairedExchange);
                if (delay > 0) {
                    log.info("[Aster] Waiting {}ms before closing (paired with {})",
                            delay, currentPairedExchange);
                    Thread.sleep(delay);
                }
            }

            OrderResult result = asterClient.closePosition(formatSymbol(symbol));
            log.info("[Asterdex] Order closed with result: {}", result);

            //PnL Calculation(Funding fees not included)
            AsterTrade tradeResult = asterClient.getTradeResultByOrderId(formatSymbol(symbol), Long.valueOf(result.getOrderId()));
            log.info("[Asterdex] Trade result for {}: {}", result.getOrderId(), tradeResult);
            double totalFee = (Double.parseDouble(tradeResult.getCommission()) * 2.0);

            result.setRealizedPnl(Double.parseDouble(tradeResult.getRealizedPnl()) - totalFee);
            result.setExitPrice(tradeResult.getPrice());

            return result;

        } catch (InterruptedException e) {
            log.error("[Aster] Interrupted during close delay", e);
            Thread.currentThread().interrupt();
            throw new ClosingPositionException("[Aster] Interrupted during close");
        } catch (Exception e) {
            throw new ClosingPositionException("[Aster] Error closing position - manual check required");
        }
    }

    @Override
    public String setLeverage(String symbol, int leverage) {
        if (asterClient.setLeverage(formatSymbol(symbol), leverage)) {
            return "[Asterdex] Leverage " + leverage + " was set for $" + symbol;
        } else throw new OpeningPositionException("[Asterdex] Error setting leverage");
    }

    @Override
    public int getMaxLeverage(String symbol, int leverage) {
        int max = asterClient.getMaxLeverage(formatSymbol(symbol));
        int current = Math.min(leverage, max);

        while (current >= 1) {
            if (asterClient.setLeverage(formatSymbol(symbol), current)) {
                return current;
            }
            current--;
        }

        throw new OpeningPositionException("[Asterdex] No allowed leverage for " + symbol);
    }

    @Override
    public double getFundingRate(String symbol) {
        return 0;
    }

    @Override
    public long getMinutesUntilFunding(String symbol) {
        return asterClient.getMinutesUntilFunding(formatSymbol(symbol));
    }

    @Override
    public FundingHistory getFundingHistory(String symbol, Direction side, long fromTimeMs, int limit) {
        return null;
    }

    @Override
    public double getMakerFee() {
        return makerFee;
    }

    @Override
    public double getTakerFee() {
        return takerFee;
    }

    @Override
    public Double calculateMaxSizeForMargin(String market, double marginUsd, int leverage, boolean isBuy) {
        return asterClient.calculateMaxSizeForMargin(
                formatSymbol(market),
                marginUsd,
                leverage,
                isBuy
        );
    }

    @Override
    public String openPositionWithSize(String market, double size, String direction) {
        try {
            if (currentPairedExchange != null) {
                int delay = getOpenDelay(currentPairedExchange);
                if (delay > 0) {
                    log.info("[Aster] Waiting {}ms before opening (paired with {})",
                            delay, currentPairedExchange);
                    Thread.sleep(delay);
                }
            }

            return asterClient.openPositionWithSize(
                    formatSymbol(market),
                    size,
                    direction
            );

        } catch (InterruptedException e) {
            log.error("[Aster] Interrupted during close delay", e);
            Thread.currentThread().interrupt();
            throw new ClosingPositionException("[Aster] Interrupted during close");
        } catch (Exception e) {
            throw new ClosingPositionException("[Aster] Error closing position - manual check required");
        }
    }

    @Override
    public double calculateFunding(String ticker, Direction direction, FundingCloseSignal signal, Double prevFunding) {
        try {
            Thread.sleep(15000);
            String symbol = formatSymbol(ticker);

            PremiumIndexResponse premium = asterClient.getPremiumIndexInfo(symbol);

            List<Position> positions = getPositions(ticker, direction);
            if (positions.isEmpty()) {
                log.debug("[Aster] No position found for {} {}", ticker, direction);
                return prevFunding;
            }

            Position pos = positions.getFirst();

            double size = pos.getSize();
            double markPrice = premium.getMarkPriceAsDouble();
            double notional = size * markPrice;
            double fundingRate = premium.getLastFundingRateAsDouble();

            boolean isLong = direction == Direction.LONG;
            double fundingPnl = isLong
                    ? -notional * fundingRate
                    : notional * fundingRate;

            log.info("[Aster] Funding: {} {} rate={}%, notional=${}, pnl=${}",
                    ticker,
                    direction,
                    String.format("%.6f", fundingRate * 100),
                    String.format("%.2f", notional),
                    String.format("%.4f", fundingPnl));

            double realFunding = asterClient.getAccumulatedFundingFee(symbol, signal.getOpenedAtMs());

            log.info("[Asterdex] Funding comparison: symbol={}, direction={}, " +
                            "calculated={}, realFromAPI={}, diff={}",
                    symbol,
                    direction,
                    String.format("%.6f", fundingPnl + prevFunding),
                    String.format("%.6f", realFunding),
                    String.format("%.6f", realFunding - (fundingPnl + prevFunding)));

//            return fundingPnl + prevFunding;
            return realFunding;

        } catch (AsterApiException e) {
            log.error("[Aster] AsterApiError calculating funding: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[Aster] Error calculating funding for {} {}: {}",
                    ticker, direction, e.getMessage());
            return prevFunding;
        }
    }

    @Override
    public String placeStopLoss(String symbol, Direction direction, double stopPrice) {
        String formattedSymbol = formatSymbol(symbol);

        String slSide = direction == Direction.LONG ? "SELL" : "BUY";
        String positionSide = direction.toString(); // "LONG" or "SHORT"

        return asterClient.placeStopLoss(
                formattedSymbol,
                slSide,
                positionSide,
                stopPrice
        );
    }

    @Override
    public String placeTakeProfit(String symbol, Direction direction, double tpPrice) {
        String formattedSymbol = formatSymbol(symbol);

        String tpSide = direction == Direction.LONG ? "SELL" : "BUY";
        String positionSide = direction.toString(); // "LONG" or "SHORT"

        return asterClient.placeTakeProfit(
                formattedSymbol,
                tpSide,
                positionSide,
                tpPrice
        );
    }

    @Override
    public boolean supportsSlTp() {
        return true;
    }

    @Override
    public boolean isFundingTimeValid(String ticker) {
        long minutes = getMinutesUntilFunding(ticker);
        return minutes <= 60;
    }

    @Override
    public List<FundingHistoryDto> getFundingHistoryForSymbol(String symbol, long startTime, long endTime) {
        List<AsterFundingHistoryDto> history = asterClient.getFundingHistory(formatSymbol(symbol), startTime, endTime).stream()
                .peek(obj -> obj.setFundingRate(obj.getFundingRate().multiply(BigDecimal.valueOf(100))))
                .toList();
        return new ArrayList<>(history);
    }
}
