package ru.exchanges;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import ru.client.extended.ExtendedClient;
import ru.dto.exchanges.*;
import ru.dto.exchanges.extended.ExtendedFundingHistoryResponse;
import ru.dto.exchanges.extended.ExtendedOrderBook;
import ru.dto.exchanges.extended.ExtendedPosition;
import ru.dto.exchanges.extended.ExtendedPositionHistory;
import ru.dto.funding.FundingCloseSignal;
import ru.dto.funding.FundingHistoryDto;
import ru.dto.funding.aster.AsterFundingHistoryDto;
import ru.exceptions.ClosingPositionException;
import ru.mapper.extended.ExtendedOrderBookMapper;
import ru.mapper.extended.ExtendedPositionMapper;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class Extended implements Exchange {

    private final ExtendedClient extendedClient;
    private static final double EXTENDED_TAKER_FEE = 0.00025; // 0.025%
    private static final double EXTENDED_MAKER_FEE = 0.0; // 0%

    private ExchangeType currentPairedExchange = null;

    @Override
    public void setPairedExchange(ExchangeType pairedWith) {
        this.currentPairedExchange = pairedWith;
    }

    @Override
    public String placeStopLoss(String symbol, Direction direction, double stopPrice) {
        return "[Extended] TP/SL not yet supported by API";
    }

    @Override
    public String placeTakeProfit(String symbol, Direction direction, double tpPrice) {
        return "[Extended] TP/SL not yet supported by API";
    }

    @Override
    public boolean supportsSlTp() {
        return false;
    }

    @Override
    public int getOpenDelay(ExchangeType pairedWith) {
        return switch (pairedWith) {
            case ASTER -> 0;  // Wait 0s for Extended to open
            case LIGHTER -> 0;   // Wait 0s for Lighter to open
            default -> 0;
        };
    }

    @Override
    public int getCloseDelay(ExchangeType pairedWith) {
        return switch (pairedWith) {
            case ASTER -> 0;  // Wait 3s for Extended to close
            case LIGHTER -> 0;   // Wait 0s for Lighter to close
            default -> 0;
        };
    }

    @Override
    public ExchangeType getType() {
        return ExchangeType.EXTENDED;
    }

    @Override
    public String getName() {
        return "Extended";
    }

    @Override
    public String formatSymbol(String ticker) {
        return ticker + "-USD";
    }

    @Override
    public Balance getBalance() {
        double balance = extendedClient.getBalance();
        return Balance.builder()
                .balance(balance)
                .margin(balance * 0.75)
                .build();
    }

    @Override
    public OrderBook getOrderBook(String symbol) {
        ExtendedOrderBook orderBook = extendedClient.getOrderBook(formatSymbol(symbol));

        return ExtendedOrderBookMapper.toOrderBook(orderBook, symbol);
    }

    @Override
    public List<Position> getPositions(String symbol, Direction side) {
        List<ExtendedPosition> ep = extendedClient.getPositions(formatSymbol(symbol), side.name());
        List<Position> positions = new ArrayList<>();
        for (ExtendedPosition pos : ep) {
            positions.add(ExtendedPositionMapper.toPosition(pos));
        }
        return positions;
    }

    @Override
    public OrderResult closePosition(String symbol, Direction currentSide) {
        try {
            if (currentPairedExchange != null) {
                int delay = getCloseDelay(currentPairedExchange);
                if (delay > 0) {
                    log.info("[Extended] Waiting {}ms before closing (paired with {})", delay, currentPairedExchange);
                    Thread.sleep(delay);
                }
            }

            long closeInitiatedAt = System.currentTimeMillis();
            OrderResult result = extendedClient.closePosition(formatSymbol(symbol), String.valueOf(currentSide));
            log.info("[ExtendedDex] Order closed with result: {}", result);

            ExtendedPositionHistory positionResult = getLastClosedPositionWithRetry(
                    formatSymbol(symbol), String.valueOf(currentSide), closeInitiatedAt);

            if (positionResult == null) {
                throw new ClosingPositionException("[Extended] Could not get closed position from history - manual check required");
            }

            log.info("[ExtendedDex] Trade result for {}: {}", result.getOrderId(), positionResult);

            result.setRealizedPnl(
                    positionResult.getRealisedPnlBreakdown().getTradePnl()
                    + positionResult.getRealisedPnlBreakdown().getCloseFees()
                    + positionResult.getRealisedPnlBreakdown().getOpenFees()
            );
            result.setExitPrice(positionResult.getExitPrice());

            return result;

        } catch (InterruptedException e) {
            log.error("[Extended] Interrupted during close delay", e);
            Thread.currentThread().interrupt();
            throw new ClosingPositionException("[Extended] Interrupted during close");
        } catch (ClosingPositionException e) {
            throw e;
        } catch (Exception e) {
            throw new ClosingPositionException("[Extended] Error closing position - manual check required");
        }
    }

    private ExtendedPositionHistory getLastClosedPositionWithRetry(String market, String side, long closeInitiatedAt) throws InterruptedException {
        for (int attempt = 1; attempt <= 8; attempt++) {
            Thread.sleep(attempt == 1 ? 5_000 : 3_000);

            ExtendedPositionHistory candidate = extendedClient.getLastClosedPosition(market, side);

            if (candidate != null
                    && candidate.getClosedTime() != null
                    && candidate.getClosedTime() >= closeInitiatedAt) {
                log.info("[Extended] Fresh closed position found on attempt {}/{}", attempt, 8);
                return candidate;
            }

            log.warn("[Extended] Attempt {}/8: stale closedTime={}, expected >={}, retrying...",
                    attempt,
                    candidate != null ? candidate.getClosedTime() : "null",
                    closeInitiatedAt);
        }

        log.error("[Extended] Could not get fresh closed position after 8 attempts (~29s total)");
        return null;
    }


    @Override
    public String setLeverage(String symbol, int leverage) {
        return extendedClient.setLeverage(formatSymbol(symbol), leverage);
    }

    @Override
    public int getMaxLeverage(String symbol, int leverage) {
        return extendedClient.getMaxLeverage(formatSymbol(symbol));
    }

    @Override
    public double getFundingRate(String symbol) {
        return 0;
    }

    @Override
    public long getMinutesUntilFunding(String symbol) {
        return 0;
    }

    @Override
    public FundingHistory getFundingHistory(String symbol, Direction side, long fromTimeMs, int limit) {
        return null;
    }

    @Override
    public double getMakerFee() {
        return EXTENDED_MAKER_FEE;
    }

    @Override
    public double getTakerFee() {
        return EXTENDED_TAKER_FEE;
    }

    @Override
    public Double calculateMaxSizeForMargin(String market, double marginUsd, int leverage, boolean isBuy) {
        return extendedClient.calculateMaxSizeForMargin(
                formatSymbol(market),
                marginUsd,
                leverage,
                isBuy);
    }

    @Override
    public String openPositionWithSize(String market, double size, String direction) {
        try {
            if (currentPairedExchange != null) {
                int delay = getOpenDelay(currentPairedExchange);
                if (delay > 0) {
                    log.info("[Extended] Waiting {}ms before opening (paired with {})",
                            delay, currentPairedExchange);
                    Thread.sleep(delay);
                }
            }

            return extendedClient.openPositionWithSize(
                    formatSymbol(market),
                    size,
                    direction
            );

        } catch (InterruptedException e) {
            log.error("[Extended] Interrupted during close delay", e);
            Thread.currentThread().interrupt();
            throw new ClosingPositionException("[Extended] Interrupted during close");
        } catch (Exception e) {
            throw new ClosingPositionException("[Extended] Error closing position - manual check required");
        }
    }

    @Override
    public double calculateFunding(String ticker, Direction direction, FundingCloseSignal signal, Double prevFunding) {
        try {
            //Waiting 60 seconds for data to load up
            Thread.sleep(60000);

            long adjustedFromTime = signal.getOpenedAtMs() + 1000;

            ExtendedFundingHistoryResponse response = extendedClient.getFundingHistory(
                    formatSymbol(ticker),
                    direction.name(),
                    adjustedFromTime,
                    1000
            );

            if (response == null) {
                log.warn("[Extended] Null response for {}", ticker);
                return 0.0;
            }

            if (response.getSummary() != null) {
                double cumulative = response.getSummary().getNetFunding();

                log.info("[Extended] Funding for {} {}: summary cumulative=${} (since {})",
                        ticker,
                        direction,
                        String.format("%.4f", cumulative),
                        signal.getOpenedAtMs());

                return cumulative;
            }

            if (response.getData() != null && !response.getData().isEmpty()) {
                double totalFunding = 0.0;
                int validPayments = 0;

                for (var payment : response.getData()) {
                    if (payment.getPaidTime() >= signal.getOpenedAtMs()) {
                        double fundingFee = Double.parseDouble(payment.getFundingFee());
                        totalFunding += fundingFee;
                        validPayments++;

                        log.debug("[Extended] Payment: time={}, fee=${}, side={}",
                                payment.getPaidTime(),
                                String.format("%.4f", fundingFee),
                                payment.getSide());
                    }
                }

                log.info("[Extended] Funding for {} {}: manual sum=${} from {} payments (filtered from total {})",
                        ticker,
                        direction,
                        String.format("%.4f", totalFunding),
                        validPayments,
                        response.getData().size());

                return totalFunding;
            }

            log.warn("[Extended] No funding data for {}", ticker);
            return 0.0;

        } catch (Exception e) {
            log.error("[Extended] Error getting funding for {} {}: {}",
                    ticker, direction, e.getMessage(), e);
            return 0.0;
        }
    }

    //All Extended tickers has 1hr funding
    @Override
    public boolean isFundingTimeValid(String ticker) {
        return true;
    }

    @Override
    public List<FundingHistoryDto> getFundingHistoryForSymbol(String symbol, long startTime, long endTime) {
        return new ArrayList<>();
    }
}
