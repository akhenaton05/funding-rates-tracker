package ru.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.dto.funding.hyperliquid.HyperliquidFundingResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class HyperliquidParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<HyperliquidFundingResponse> parseMetaAndAssetCtxs(String rawJson) throws Exception {
        JsonNode root = objectMapper.readTree(rawJson);

        JsonNode meta = root.get(0);
        JsonNode universe = meta.get("universe");
        JsonNode assetCtxs = root.get(1);

        List<HyperliquidFundingResponse> result = new ArrayList<>();
        int size = Math.min(universe.size(), assetCtxs.size());

        for (int i = 0; i < size; i++) {
            JsonNode coinMeta = universe.get(i);
            JsonNode ctx = assetCtxs.get(i);

            HyperliquidFundingResponse row = new HyperliquidFundingResponse();
            row.setSymbol(coinMeta.path("name").asText());
            row.setFundingRate(toBigDecimal(ctx.get("funding")));
            /*row.setSzDecimals(coinMeta.path("szDecimals").asInt());
            row.setMaxLeverage(coinMeta.path("maxLeverage").asInt());
            row.setMarkPx(toBigDecimal(ctx.get("markPx")));
            row.setMidPx(toBigDecimal(ctx.get("midPx")));
            row.setOpenInterest(toBigDecimal(ctx.get("openInterest")));
            row.setOraclePx(toBigDecimal(ctx.get("oraclePx")));
            row.setDayNtlVlm(toBigDecimal(ctx.get("dayNtlVlm")));*/

            result.add(row);
        }

        return result;
    }

    private BigDecimal toBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value);
    }
}