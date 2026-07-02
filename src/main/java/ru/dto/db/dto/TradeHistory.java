package ru.dto.db.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.dto.db.dto.TickerStats;

import java.time.Duration;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeHistory {

    // Мета
    private int totalTrades;
    private int periodDays;
    private double totalVolume;

    // P&L
    private double totalPnl;
    private double pnlToVolumePercent;
    private double avgPnlPerTrade;
    private double bestTrade;
    private double worstTrade;

    // Эффективность
    private int wins;
    private int losses;
    private double winRate;
    private int currentStreak;

    // Фандинг
    private double totalFunding;
    private double fundingToPnlPercent;
    private double avgOpenRate;
    private double avgCloseRate;
    private double avgRateDelta;

    // Удержание
    private Duration avgHoldTime;
    private Duration maxHoldTime;
    private String   maxHoldTicker;
    private Duration minHoldTime;
    private String   minHoldTicker;

    // Тикеры
    private List<TickerStats> tickerStats;
}
