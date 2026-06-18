package ru.dto.funding.lighter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.dto.funding.FundingApiResponseDto;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterFundingResponse extends FundingApiResponseDto {
    private String symbol;

    @JsonProperty("exchange")
    private String exchange;

    @JsonProperty("rate")
    private BigDecimal fundingRate;

    @JsonIgnore
    public BigDecimal getFundingUI() {
        if (fundingRate == null) {
            return BigDecimal.ZERO;
        }

        return fundingRate
                .divide(BigDecimal.valueOf(8), 12, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros();
    }
}
