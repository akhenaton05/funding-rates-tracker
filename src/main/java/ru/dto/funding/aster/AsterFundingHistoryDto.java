package ru.dto.funding.aster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.dto.funding.FundingHistoryDto;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class AsterFundingHistoryDto extends FundingHistoryDto {
    @JsonProperty("symbol")
    private String symbol;

    @JsonProperty("fundingRate")
    private BigDecimal fundingRate;

    @JsonProperty("fundingTime")
    private long time;
}
