package ru.exchanges;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import ru.client.lighter.LighterClient;
import ru.dto.exchanges.*;
import ru.dto.exchanges.lighter.LighterOrderBookResponse;
import ru.dto.exchanges.lighter.LighterPosition;
import ru.dto.funding.FundingCloseSignal;
import ru.dto.funding.FundingHistoryDto;
import ru.dto.funding.aster.AsterFundingHistoryDto;
import ru.dto.funding.lighter.LighterFundingHistoryDto;
import ru.mapper.lighter.LighterOrderBookMapper;
import ru.mapper.lighter.LighterPositionMapper;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class Lighter implements Exchange {

    private final LighterClient lighterClient;
    private static final double LIGHTER_TAKER_FEE = 0.0; // 0%
    private static final double LIGHTER_MAKER_FEE = 0.0; // 0%

    private ExchangeType currentPairedExchange = null;


    @Override
    public ExchangeType getType() {
        return ExchangeType.LIGHTER;
    }

    @Override
    public String getName() {
        return "Lighter";
    }

    @Override
    public String formatSymbol(String ticker) {
        return ticker;
    }

    @Override
    public Balance getBalance() {
        double balance = lighterClient.getBalance();

        return Balance.builder()
                .balance(balance)
                .margin(balance * 0.85)
                .build();
    }

    @Override
    public OrderBook getOrderBook(String symbol) {
        try {
            LighterOrderBookResponse orderBook = lighterClient.getOrderBook(formatSymbol(symbol));
            return LighterOrderBookMapper.toOrderBook(orderBook, symbol);
        } catch (Exception e) {
            log.error("[Lighter] Error getting Orderbook for {}", symbol, e);
            return null;
        }
    }

    @Override
    public List<Position> getPositions(String symbol, Direction side) {
        List<LighterPosition> positions = lighterClient.getPositions(formatSymbol(symbol), side.toString());
        List<Position> result = new ArrayList<>();
        for(LighterPosition pos : positions) {
            result.add(LighterPositionMapper.toPosition(pos));
        }
        log.info("[LighterDex] LighterPosition: {}", positions);
        log.info("[LighterDex] Position: {}", result);
        return result;
    }

    @Override
    public OrderResult closePosition(String symbol, Direction currentSide) {
        //Lighter calculates PnL right before closing the position
        return lighterClient.closePositionWithResult(formatSymbol(symbol), currentSide.toString());
    }

    @Override
    public String setLeverage(String symbol, int leverage) {
        return lighterClient.setLeverage(formatSymbol(symbol), leverage);
    }

    @Override
    public int getMaxLeverage(String symbol, int leverage) {
        return lighterClient.getMaxLeverage(formatSymbol(symbol));
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
        return LIGHTER_MAKER_FEE;
    }

    @Override
    public double getTakerFee() {
        return LIGHTER_TAKER_FEE;
    }

    @Override
    public Double calculateMaxSizeForMargin(String market, double marginUsd, int leverage, boolean isBuy) {
        return lighterClient.calculateMaxSizeForMargin(formatSymbol(market), marginUsd, leverage, isBuy);
    }

    @Override
    public String openPositionWithSize(String market, double size, String direction) {
        return lighterClient.openPositionWithSize(formatSymbol(market), size, direction);
    }

    @Override
    public double calculateFunding(String ticker, Direction direction, FundingCloseSignal signal, Double prevFunding) {
        try {
            //Waiting 10 seconds for data to load up
            Thread.sleep(10000);
        } catch(InterruptedException e) {
            throw new IllegalArgumentException();
        }
        double positionValue = Double.parseDouble(lighterClient.getPositions(formatSymbol(ticker), direction.toString()).getFirst().getPositionValue());
        double fundingRate = lighterClient.getFundingRate(formatSymbol(ticker));

        boolean isLong = direction == Direction.LONG;
        double fundingPnl = isLong
                ? positionValue * fundingRate
                : -positionValue * fundingRate;

        log.info("[Lighter] Funding: {} {} rate={}, posValue=${}, funding=${}",
                ticker, direction,
                String.format("%.6f", fundingRate),
                String.format("%.2f", positionValue),
                String.format("%.4f", fundingPnl));

        return fundingPnl + prevFunding;
    }

    @Override
    public int getOpenDelay(ExchangeType pairedWith) {
        return switch (pairedWith) {
            case ASTER -> 0;  // Wait 0s for Aster to close
            case EXTENDED -> 3;   // Wait 0s for Extended to close
            default -> 0;
        };
    }

    @Override
    public int getCloseDelay(ExchangeType pairedWith) {
        return switch (pairedWith) {
            case ASTER -> 0;  // Wait 0s for Aster to close
            case EXTENDED -> 4;   // Wait 0s for Extended to close
            default -> 0;
        };
    }

    @Override
    public void setPairedExchange(ExchangeType pairedWith) {
        currentPairedExchange = pairedWith;
    }

    @Override
    public String placeStopLoss(String symbol, Direction direction, double stopPrice) {
        return "";
    }

    @Override
    public String placeTakeProfit(String symbol, Direction direction, double tpPrice) {
        return "";
    }

    @Override
    public boolean supportsSlTp() {
        return false;
    }

    //All Lighter funding has 1hr interval
    @Override
    public boolean isFundingTimeValid(String ticker) {
        return true;
    }

    @Override
    public List<FundingHistoryDto> getFundingHistoryForSymbol(String symbol, long startTime, long endTime) {
        startTime = startTime / 1000;
        endTime = endTime / 1000;
        List<LighterFundingHistoryDto> history = lighterClient.getFundingHistory(formatSymbol(symbol), startTime, endTime);
        return new ArrayList<>(history);
    }
}
