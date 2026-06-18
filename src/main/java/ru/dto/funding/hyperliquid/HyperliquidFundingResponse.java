package ru.dto.funding.hyperliquid;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import ru.dto.funding.FundingApiResponseDto;

import java.math.BigDecimal;

@Data
public class HyperliquidFundingResponse extends FundingApiResponseDto {
    private String symbol;
    private BigDecimal fundingRate;
    //private int szDecimals;
    //private int maxLeverage;
    //private BigDecimal markPx;
    //private BigDecimal midPx;
    //private BigDecimal openInterest;
    //private BigDecimal oraclePx;
    //private BigDecimal dayNtlVlm;

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