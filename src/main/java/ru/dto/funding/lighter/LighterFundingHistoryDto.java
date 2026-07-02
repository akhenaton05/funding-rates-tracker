package ru.dto.funding.lighter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.dto.funding.FundingHistoryDto;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class LighterFundingHistoryDto extends FundingHistoryDto {
    @JsonProperty("coin")
    private String symbol;

    @JsonProperty("unix")
    private long time;

    @JsonProperty("fundingRate")
    private BigDecimal fundingRate;
}