package ru.exchanges;

import ru.dto.exchanges.*;
import ru.dto.funding.FundingCloseSignal;
import ru.dto.funding.FundingHistoryDto;
import ru.dto.funding.FundingOpenSignal;
import ru.dto.funding.PositionPnLData;

import java.util.List;

public interface Exchange {

    ExchangeType getType();

    String getName();

    String formatSymbol(String ticker);

    Balance getBalance();

    OrderBook getOrderBook(String symbol);

    List<Position> getPositions(String symbol, Direction side);

    OrderResult closePosition(String symbol, Direction currentSide);

    String setLeverage(String symbol, int leverage);

    int getMaxLeverage(String symbol, int leverage);

    double getFundingRate(String symbol);

    long getMinutesUntilFunding(String symbol);

    FundingHistory getFundingHistory(String symbol, Direction side, long fromTimeMs, int limit);

    double getMakerFee();

    double getTakerFee();

    Double calculateMaxSizeForMargin(String market, double marginUsd, int leverage, boolean isBuy);

    String openPositionWithSize(String market, double size, String direction);

    double calculateFunding(String ticker, Direction direction, FundingCloseSignal signal, Double prevFunding);

    int getOpenDelay(ExchangeType pairedWith);

    int getCloseDelay(ExchangeType pairedWith);

    void setPairedExchange(ExchangeType pairedWith);

    String placeStopLoss(String symbol, Direction direction, double stopPrice);

    String placeTakeProfit(String symbol, Direction direction, double tpPrice);

    boolean supportsSlTp();

    boolean isFundingTimeValid(String ticker);

    List<FundingHistoryDto> getFundingHistoryForSymbol(String symbol, long startTime, long endTime);
}
