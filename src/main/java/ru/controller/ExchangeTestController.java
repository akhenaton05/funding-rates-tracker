package ru.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.client.aster.AsterClient;
import ru.client.extended.ExtendedClient;
import ru.client.hyperliquid.HyperliquidClient;
import ru.client.lighter.LighterClient;
import ru.dto.exchanges.Direction;
import ru.dto.exchanges.Position;
import ru.dto.exchanges.aster.AsterTrade;
import ru.dto.exchanges.extended.ExtendedPositionHistory;
import ru.dto.funding.aster.AsterFundingResponse;
import ru.dto.funding.hyperliquid.HyperliquidFundingResponse;
import ru.dto.funding.lighter.LighterFundingResponse;
import ru.exchanges.Asterdex;
import ru.exchanges.Extended;
import ru.exchanges.Hyperliquid;
import ru.exchanges.Lighter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class ExchangeTestController {

    private final AsterClient asterClient;
    private final ExtendedClient extendedClient;
    private final LighterClient lighterClient;
    private final HyperliquidClient hyperliquidClient;
    private final Extended extended;
    private final Asterdex aster;
    private final Lighter lighter;
    private final Hyperliquid hyper;

    // GET /test/aster/pnl?symbol=BTCUSDT&orderId=123456789
    @GetMapping("/aster/pnl")
    public AsterTrade testAsterPnl(
            @RequestParam(value = "symbol") String symbol,
            @RequestParam(value = "orderId", required = false) Long orderId
    ) {
        log.info("Test Aster getRealizedPnl: symbol={}, orderId={}", symbol, orderId);

        AsterTrade pnl = asterClient.getTradeResultByOrderId(symbol, orderId);

        System.out.println(pnl);

        return pnl;
    }

    // GET /test/aster/fundings
    @GetMapping("/aster/fundings")
    public List<AsterFundingResponse> getAsterFundingRates() {
        log.info("Test Aster funding rates");

        return asterClient.getFundingList().stream()
                .peek(item -> item.setFundingRate(item.getFundingUI()))
                .toList();
    }

    // GET /test/aster/fundings
    @GetMapping("/lighter/fundings")
    public List<LighterFundingResponse> getLighterFundingRates() {
        log.info("Test Lighter funding rates");

        return lighterClient.getFundingList().stream()
                .peek(item -> item.setFundingRate(item.getFundingUI()))
                .toList();
    }

    // GET /test/aster/fundings
    @GetMapping("/hyperliquid/fundings")
    public List<HyperliquidFundingResponse> getHyperFundingRates() {
        log.info("Test Lighter funding rates");

        return hyperliquidClient.getFundingList().stream()
                .peek(item -> item.setFundingRate(item.getFundingUI()))
                .toList();
    }

    // GET /test/extended/position/history?market=4-USD&side=LONG
    @GetMapping("/extended/market")
    public ResponseEntity<?> testMaxLeverage(@RequestParam(value = "market") String market) {
//        ExtendedMarketStats stats = extendedClient.getMarketStats(market);
        extendedClient.getMaxLeverage(market);

        return ResponseEntity.ok("OK");
    }

    // GET /test/extended/position/history?market=4-USD&side=LONG
    @GetMapping("/extended/position/history")
    public ResponseEntity<?> testExtendedPositionHistory(
            @RequestParam(value = "market") String market,
            @RequestParam(value = "side", defaultValue = "LONG") String side
    ) {
        log.info("Test Extended getLastClosedPosition: market={}, side={}", market, side);

        ExtendedPositionHistory position = extendedClient.getLastClosedPosition(market, side);

        if (position == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "NOT_FOUND",
                    "market", market,
                    "side", side
            ));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "OK");
        response.put("market", position.getMarket());
        response.put("side", position.getSide());
        response.put("realisedPnl", position.getRealisedPnl());
        response.put("realisedPnlBreakdown", position.getRealisedPnlBreakdown());
        response.put("openPrice", position.getOpenPrice());
        response.put("exitPrice", position.getExitPrice());
        response.put("size", position.getSize());
        response.put("leverage", position.getLeverage());
        response.put("closedTime", position.getClosedTime());
        response.put("createdTime", position.getCreatedTime());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/test/aster/leverage")
    public ResponseEntity<String> testAsterSetLeverage(
            @RequestParam("symbol") String symbol,
            @RequestParam("leverage") int leverage) {

        log.info("[Test] Setting Aster leverage: symbol={}, leverage={}", symbol, leverage);

        try {
            asterClient.setLeverage(symbol, leverage);
            return ResponseEntity.ok("{\"status\": \"ok\", \"symbol\": \"" + symbol + "\", \"leverage\": " + leverage + "}");
        } catch (Exception e) {
            return ResponseEntity.ok("{\"status\": \"error\", \"message\": \"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    @PostMapping("/hyper/leverage")
    public ResponseEntity<?> testHyperSetLeverage(
            @RequestParam("symbol") String symbol,
            @RequestParam("leverage") int leverage) {

        log.info("[Controller] Setting Hyper leverage: symbol={}, leverage={}", symbol, leverage);
        return ResponseEntity.ok(hyper.setLeverage(symbol, leverage));
    }

    //Positions Tests
    // GET /test/extended/position/?market=4-USD&side=LONG
    @GetMapping("/extended/position")
    public ResponseEntity<?> getExtendedPosition(@RequestParam(value = "market") String market,
                                                 @RequestParam(value = "side") Direction side) {
        log.info("[Controller] Extended position response: {}", extended.getPositions(market, side));

        return ResponseEntity.ok("OK");
    }

    @GetMapping("/aster/position")
    public ResponseEntity<?> getAsterPosition(@RequestParam(value = "market") String market,
                                              @RequestParam(value = "side") Direction side) {
        log.info("[Controller] Aster position response: {}", aster.getPositions(market, side));
        log.info("[Controller] Aster entry price: {}", aster.getPositions(market, side).getFirst().getEntryPrice());

        return ResponseEntity.ok("OK");
    }

    @GetMapping("/lighter/position")
    public ResponseEntity<?> getLighterPosition(@RequestParam(value = "market") String market,
                                                @RequestParam(value = "side") Direction side) {
        log.info("[Controller] Lighter position response: {}", lighter.getPositions(market, side));
        log.info("[Controller] Lighter entry price: {}", lighter.getPositions(market, side).getFirst().getEntryPrice());

        return ResponseEntity.ok("OK");
    }

    @GetMapping("/hyper/position")
    public ResponseEntity<?> getHyperPosition(@RequestParam(value = "market") String market,
                                                @RequestParam(value = "side") Direction side) {
        List<Position> pos = hyper.getPositions(market, side);
        log.info("[Controller] Hyper position response: {}", pos);

        return ResponseEntity.ok(pos);
    }

    @PostMapping("/hyper/open")
    public ResponseEntity<?> testHyperOpen(
            @RequestParam("symbol") String symbol,
            @RequestParam("size") Double size,
            @RequestParam("direction") Direction direction) {

        log.info("[Controller] Opening Hyper pos: symbol={}, size={}, direction={}", symbol, size, direction);
        return ResponseEntity.ok(hyper.openPositionWithSize(symbol, size, String.valueOf(direction)));
    }

    @PostMapping("/hyper/close")
    public ResponseEntity<?> testHyperClose(
            @RequestParam("symbol") String symbol,
            @RequestParam("direction") Direction direction) {

        log.info("[Controller]Closing Hyper pos: symbol={}, direction={}", symbol, direction);
        return ResponseEntity.ok(hyper.closePosition(symbol, direction));
    }
}
