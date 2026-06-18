package ru.dto.funding.lighter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LighterFundingRatesResponse {

    @JsonProperty("code")
    private int status;

    @JsonProperty("message")
    private String message;

    @JsonProperty("funding_rates")
    private List<LighterFundingResponse> fundingRates;
}