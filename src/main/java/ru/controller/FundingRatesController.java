package ru.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.service.FundingArbitrageService;
import ru.service.TelegramChatService;
import ru.utils.FundingApiParser;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class FundingRatesController {
    private final FundingApiParser fundingApiParser;

    // GET /test/aster/fundings
    @GetMapping("/funding/get-rates")
    public Map<String, Map<String, Object>> getFundingRates() {
        log.info("Test funding rates mapping");
        return fundingApiParser.getFundingRates();
    }
}
