package ru.event;

import lombok.Builder;
import lombok.Data;
import ru.dto.exchanges.Direction;
import ru.dto.funding.PositionPnLData;

import java.math.BigDecimal;

@Data
@Builder
public class PositionUpdateEvent {
    private final String positionId;
    private final String ticker;
    private final String ex1Name;
    private final String ex2Name;
    private final String mode;
    private final Direction firstDirection;
    private final Direction secondDirection;
    private final double balance;
    private final double openFundingRate;
    private final double currentFundingRate;
    private final long openedAtMs;
    private final PositionPnLData pnlData;
    private final BigDecimal historicalFundingRate;
}