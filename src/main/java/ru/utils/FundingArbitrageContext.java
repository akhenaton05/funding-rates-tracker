package ru.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.config.FundingConfig;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component

public class FundingArbitrageContext {

    private final Set<Long> subscriberIds = ConcurrentHashMap.newKeySet();
    private final Set<String> tickerBlacklist = ConcurrentHashMap.newKeySet();
    private final Set<String> disabledExchanges = ConcurrentHashMap.newKeySet();

    public FundingArbitrageContext(FundingConfig fundingConfig) {
        if (fundingConfig.getTickerBlacklist() != null) {
            fundingConfig.getTickerBlacklist().stream()
                    .map(String::toUpperCase)
                    .forEach(tickerBlacklist::add);
            log.info("Loaded {} tickers from blacklist config: {}",
                    tickerBlacklist.size(), tickerBlacklist);
        }
    }

    public void addToBlacklist(String ticker) {
        tickerBlacklist.add(ticker.toUpperCase());
        log.info("Ticker added to blacklist: {}", ticker);
    }

    public void removeFromBlacklist(String ticker) {
        tickerBlacklist.remove(ticker.toUpperCase());
        log.info("Ticker removed from blacklist: {}", ticker);
    }

    public boolean isBlacklisted(String ticker) {
        return tickerBlacklist.contains(ticker.toUpperCase());
    }

    public Set<String> getTickerBlacklist() {
        return Collections.unmodifiableSet(tickerBlacklist);
    }

    public void addSubscriberId(Long chatId) {
        if (subscriberIds.add(chatId)) {
            log.info("New subscriber added: {}", chatId);
        } else {
            log.info("User {} already subscribed", chatId);
        }
    }

    public void removeSubscriberId(Long chatId) {
        if (subscriberIds.remove(chatId)) {
            log.info("Subscriber removed: {}", chatId);
        }
    }

    public Set<Long> getSubscriberIds() {
        return Collections.unmodifiableSet(subscriberIds);
    }

    public void disableExchange(String exchange) {
        disabledExchanges.add(exchange.toLowerCase());
        log.info("Exchange disabled: {}", exchange);
    }

    public void enableExchange(String exchange) {
        disabledExchanges.remove(exchange.toLowerCase());
        log.info("Exchange enabled: {}", exchange);
    }

    public Set<String> getDisabledExchanges() {
        return Collections.unmodifiableSet(disabledExchanges);
    }

    public boolean isExchangeDisabled(String exchange) {
        return disabledExchanges.contains(exchange.toLowerCase());
    }
}
