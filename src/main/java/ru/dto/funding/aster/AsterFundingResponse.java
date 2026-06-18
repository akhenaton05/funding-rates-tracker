package ru.dto.funding.aster;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.dto.funding.FundingApiResponseDto;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsterFundingResponse extends FundingApiResponseDto {
    @JsonProperty("symbol")
    private String symbol;

    @JsonProperty("lastFundingRate")
    private BigDecimal fundingRate;

    @JsonProperty("nextFundingTime")
    private Long nextFundingTime;

    @JsonProperty("time")
    private Long time;

    @JsonIgnore
    public BigDecimal getFundingUI() {
        if (fundingRate == null) {
            return BigDecimal.ZERO;
        }
        return fundingRate
                .multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros();
    }
}
