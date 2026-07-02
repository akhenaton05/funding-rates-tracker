package ru.dto.funding;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class FundingHistoryDto {
    private String symbol;
    private BigDecimal fundingRate;
    private long time;
}
