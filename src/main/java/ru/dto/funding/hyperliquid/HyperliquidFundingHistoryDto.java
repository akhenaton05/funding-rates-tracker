package ru.dto.funding.hyperliquid;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.dto.funding.FundingHistoryDto;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class HyperliquidFundingHistoryDto extends FundingHistoryDto {
    @JsonProperty("coin")
    private String symbol;

    @JsonProperty("fundingRate")
    private BigDecimal fundingRate;

    @JsonProperty("premium")
    private BigDecimal premium;

    @JsonProperty("time")
    private long time;
}
