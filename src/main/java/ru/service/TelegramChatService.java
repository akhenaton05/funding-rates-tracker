package ru.service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.config.*;
import ru.dto.db.dto.TickerStats;
import ru.dto.db.dto.TradeHistory;
import ru.dto.db.model.Period;
import ru.dto.exchanges.*;
import ru.dto.funding.*;
import ru.event.*;
import ru.utils.FundingArbitrageContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TelegramChatService extends TelegramLongPollingBot {

    private final TelegramBotConfig telegramBotConfig;
    private final FundingArbitrageContext fundingContext;
    private final FundingArbitrageService fundingService;
    private final ExchangesService exchangesService;
    private final TradeHistoryService tradeHistoryService;
    private final ScheduledExecutorService deleteScheduler = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, Integer> positionMessageIds = new ConcurrentHashMap<>();


    public TelegramChatService(TelegramBotConfig telegramBotConfig,
                               FundingArbitrageContext fundingContext,
                               FundingArbitrageService fundingService,
                               ExchangesService exchangesService,
                               TradeHistoryService tradeHistoryService,
                               DefaultBotOptions botOption) {
        super(botOption);
        this.telegramBotConfig = telegramBotConfig;
        this.fundingContext = fundingContext;
        this.fundingService = fundingService;
        this.exchangesService = exchangesService;
        this.tradeHistoryService = tradeHistoryService;
    }

    @Override
    public String getBotUsername() {
        String username = telegramBotConfig.getBotUsername();
        return username.startsWith("@") ? username.substring(1) : username;
    }

    @Override
    public String getBotToken() {
        return telegramBotConfig.getBotToken();
    }

    @SneakyThrows
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            Long chatId = message.getChatId();

            log.info("Received message from {}", chatId);

            if (message.hasText()) {
                String userMessage = message.getText();
                log.info("Text message: {}", userMessage);

                if (userMessage.startsWith("/")) {
                    handleCommand(chatId, userMessage);
                }
            }
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
        }
    }


    private void handleCommand(Long chatId, String command) {
        String[] parts = command.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/track" -> addSubscriberId(chatId);
            case "/untrack" -> fundingContext.removeSubscriberId(chatId);
            case "/rates" -> sendRates(chatId);
            case "/trades" -> getTrades(chatId);
            case "/close" -> closePositionById(chatId, parts);
            case "/closeall" -> closeAllPositions();
            case "/pnl" -> calculatePositionPnl(chatId, parts);
            case "/balance" -> getExchangesBalance(chatId);
            case "/history" -> getTradeHistory(chatId);
            case "/blacklist" -> addToBlacklist(chatId, parts);
            case "/blacklist_remove" -> removeFromBlacklist(chatId, parts);
            case "/exchange_disable" -> disableExchange(chatId, parts);
            case "/exchange_enable" -> enableExchange(chatId, parts);
            case "/exchanges" -> showExchanges(chatId);
        }
    }

    private void sendRates(Long chatId) {
        log.info("[Telegram] Request to get funding rates received");
        sendTypingAction(chatId);

        try {
            List<ArbitrageRates> rates = fundingService.calculateArbitrageRates();

            if (rates.isEmpty()) {
                sendMessage(chatId, "❌ No arbitrage rates available");
                return;
            }

            StringBuilder result = new StringBuilder();
            result.append("```\n");

            // Header
            result.append(String.format("%-7s |  OI  | %7s | %-14s\n",
                    "Ticker", "Spread", "Pair"));
            result.append("-".repeat(40)).append("\n");

            // Data
            rates.stream()
                    .limit(10)
                    .forEach(opp -> {
                        String oi = opp.getOiRank() != null ? "#" + String.format("%-3d", opp.getOiRank()) : "-  ";
                        String firstDir = opp.getFirstDirection().equals(Direction.LONG) ? "↑" : "↓";
                        String secondDir = opp.getSecondDirection().equals(Direction.LONG) ? "↑" : "↓";
                        result.append(String.format(
                                "%-7s | %s | %6.2f%% | %s/%s\n",
                                opp.getSymbol(),
                                oi,
                                opp.getArbitrageRate(),
                                ExchangeType.abbreviate(opp.getFirstExchange().getDisplayName()) + firstDir,
                                ExchangeType.abbreviate(opp.getSecondExchange().getDisplayName()) + secondDir
                        ));
                    });

            result.append("```");

            sendMessageAndScheduleDelete(chatId, result.toString(), 4);

        } catch (Exception e) {
            sendMessage(chatId, "❌ Error getting data");
            log.error("Error sending rates", e);
        }
    }

    private void calculatePositionPnl(Long chatId, String[] parts) {
        //Checking parameter
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            sendMessage(chatId,
                    """
                            🤖 *FundingBot:* Invalid position ID format
                            
                            *Format:* `P-XXXX` (e.g. `P-0001`)
                            
                            Use /trades to see active positions""");
            return;
        }

        String positionId = parts[1].trim().toUpperCase();

        //Checking the format ID (P-0001, P-0002...)
        if (!positionId.matches("P-\\d{4}")) {
            sendMessage(chatId,
                    """
                            🤖 *FundingBot:* Invalid position ID format
                            
                            *Format:* `P-XXXX` (e.g. `P-0001`)
                            
                            Use /trades to see active positions""");
            return;
        }
        PositionPnLData posData = exchangesService.pnlPositionCalculator(positionId);

        sendMessageAndScheduleDelete(chatId, validateCurrentPnl(posData), 3);
    }

    private void getExchangesBalance(Long chatId) {
        log.info("[Telegram] Request to get exchanges balances");
        Map<String, Double> result = exchangesService.getExchangesBalance();

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 *FundingBot:* Exchange Balances 💰\n\n");

        result.forEach((exchange, balance) -> {
            if (exchange.equals("TOTAL")) return;
            sb.append(String.format("\uD83D\uDDFF *%s:* $%.2f\n", exchange, balance));
        });

        sb.append(String.format("💵 *Total: * $%.2f", result.getOrDefault("TOTAL", 0.0)));

        sendMessage(chatId, sb.toString());
    }

    private void closeAllPositions() {
        log.info("[Telegram] Request to close all positions");
        exchangesService.closeAllPositions();
    }

    private void closePositionById(Long chatId, String[] parts) {
        log.info("[Telegram] Close by ID request from chat {}", chatId);

        //Checking parameter
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            sendMessage(chatId,
                    """
                            🤖 *FundingBot:* *Usage:* `/close <position_id>`
                            
                            *Example:* `/close P-0001`
                            
                            Use /trades to see active positions""");
            return;
        }

        String positionId = parts[1].trim().toUpperCase();

        //Checking the format ID (P-0001, P-0002...)
        if (!positionId.matches("P-\\d{4}")) {
            sendMessage(chatId,
                    """
                            🤖 *FundingBot:* Invalid position ID format
                            
                            *Format:* `P-XXXX` (e.g. `P-0001`)
                            
                            Use /trades to see active positions""");
            return;
        }

        exchangesService.closePositionById(positionId);
    }

    public void sendMessage(Long chatId, String text) {
        sendTypingAction(chatId);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("Markdown");

        try {
            execute(message);
            log.info("[Telegram] Sent message to Telegram chat {}: {}", chatId, text);
        } catch (TelegramApiException e) {
            log.error("[Telegram] Failed to send message to Telegram chat {}: {}", chatId, e.getMessage());
        }
    }

    public Integer sendMessageAndGetId(Long chatId, String text) {
        sendTypingAction(chatId);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("Markdown");

        try {
            return execute(message).getMessageId();
        } catch (TelegramApiException e) {
            log.error("[Telegram] Failed to send message to Telegram chat {}: {}", chatId, e.getMessage());
            return 0;
        }
    }

    private void sendTypingAction(Long chatId) {
        try {
            SendChatAction chatAction = new SendChatAction();
            chatAction.setChatId(chatId);
            chatAction.setAction(ActionType.TYPING);
            execute(chatAction);
        } catch (Exception e) {
            log.debug("[Telegram] Could not send typing action", e);
        }
    }

    @EventListener
    @Async
    public void handleFundingAlert(FundingAlertEvent event) {
        log.info("[Telegram] Received funding alert event for chat {}", event.getChatId());
        sendMessageAndScheduleDelete(event.getChatId(), formatAlert(event.getMessage()), 6);
    }

    @EventListener
    @Async
    public void handlePnLThreshold(PnLThresholdEvent event) {
        log.info("[Telegram] P&L threshold event for {}: {}%",
                event.getPositionId(),
                String.format("%.2f", event.getThresholdPercent()));

        String message = formatPnLThresholdMessage(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            sendMessageAndScheduleDelete(chatId, message, 5);
        }
    }

    @EventListener
    @Async
    public void handlePositionNotification(PositionNotificationEvent event) {
        log.info("[Telegram] Position event for {}", event.getPositionId());

        String message = formatNotificationEvent(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            sendMessageAndScheduleDelete(chatId, message, 20);
        }
    }

    private void editMessage(Long chatId, Integer messageId, String text) {
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId);
            edit.setMessageId(messageId);
            edit.setText(text);
            edit.setParseMode("Markdown");
            execute(edit);
        } catch (TelegramApiException e) {
            if (!e.getMessage().contains("message is not modified")) {
                log.warn("Telegram edit failed: {}", e.getMessage());
            }
        }
    }

    @EventListener
    @Async
    public void dynamicOpeningPositionListener(PositionOpeningEvent event) {
        log.info("[Telegram] Position opening event for {}", event.getPositionId());

        String message = formatOpeningPositionEvent(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            Integer msgId = sendMessageAndGetId(chatId, message);

            if (msgId != null) {
                positionMessageIds.put(event.getPositionId(), msgId);
            }
        }
    }

    @EventListener
    @Async
    public void dynamicOpenedPositionListener(PositionOpenedEvent event) {
        log.info("[Telegram] Position opened event for {}", event.getPositionId());

        String message = formatOpenedPositionEvent(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            Integer msgId = positionMessageIds.get(event.getPositionId());
            editMessage(chatId, msgId, message);

            if (!event.isSuccess()) {
                scheduleDelete(chatId, msgId, 5);
            }

            if (msgId != null && event.isSuccess()) {
                positionMessageIds.put(event.getPositionId(), msgId);
            }
        }

        if (!event.isSuccess()) {
            positionMessageIds.remove(event.getPositionId());
        }
    }

    @EventListener
    @Async
    public void dynamicClosedPositionListener(PositionClosedEvent event) {
        log.info("[Telegram] Position closed event for {}", event.getPositionId());

        String message = formatPositionClosedMessage(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            Integer msgId = positionMessageIds.get(event.getPositionId());
            editMessage(chatId, msgId, message);

            if (msgId != null) {
                positionMessageIds.put(event.getPositionId(), msgId);
            }
        }
        positionMessageIds.remove(event.getPositionId());
    }

    @EventListener
    @Async
    public void dynamicUpdatePositionListener(PositionUpdateEvent event) {
        log.info("[Telegram] Position update event for {}", event.getPositionId());

        String message = formatLiveUpdate(event);

        for (Long chatId : fundingContext.getSubscriberIds()) {
            Integer msgId = positionMessageIds.get(event.getPositionId());
            editMessage(chatId, msgId, message);

            if (Objects.nonNull(msgId)) {
                positionMessageIds.put(event.getPositionId(), msgId);
            }
        }
    }

    private String formatOpeningPositionEvent(PositionOpeningEvent event) {
        return String.format(
                "🤖 *[FundingBot]:* Opening %s position in %s ⏳",
                event.getTicker(),
                event.getMode()
        );
    }

    private String formatOpenedPositionEvent(PositionOpenedEvent event) {
        if (!event.isSuccess()) {
            if (Objects.nonNull(event.getResult()) &&
                    event.getResult().contains("No balance available to open position")) {
                return "🤖 *[FundingBot]:* No margin available to open position";
            } else if (Objects.nonNull(event.getResult()) &&
                    event.getResult().contains("More than an hour until funding, position not opened")) {
                return "🤖 *[FundingBot]:* Funding payment in more than an hour, position wasn't opened";
            }

            return String.format(
                    "🤖 *FundingBot:* Position Opening Failed ❌\n\n" +
                            "*ID:* `%s`\n" +
                            "*Mode:* %s\n" +
                            "*Ticker:* %s\n" +
                            "*Error:* %s\n",
                    event.getPositionId(),
                    event.getMode(),
                    event.getTicker(),
                    event.getResult()
            );
        }

        return String.format(
                "🤖 *[FundingBot]:* Position Opened ✅\n\n" +
                        "*ID:* `%s`\n" +
                        "*Mode:* %s\n" +
                        "*Ticker:* %s\n" +
                        "*Margin Used:* %.2f USD\n" +
                        "*Entry:* %s\n" +
                        "*Funding Rate:* %.2f%%\n",
                event.getPositionId(),
                event.getMode(),
                event.getTicker(),
                event.getBalanceUsed(),
                getEntrySpreadInfo(event.getData().getFirstSnapshot(), event.getData().getSecondSnapshot(), event.getData().getEntrySpreadPct()),
                event.getRate()
        );
    }

    private String formatPositionClosedMessage(PositionClosedEvent event) {
        String sign = event.getPnl() >= 0 ? "+" : "";
        if (!event.isSuccess()) {
            return String.format(
                    "🤖 *FundingBot:* Position Close Error ❌\n\n" +
                            "*ID:* `%s`\n" +
                            "*Mode:* %s\n" +
                            "*Ticker:* %s\n" +
                            "*Status:* Manual check required!",
                    event.getPositionId(),
                    event.getMode(),
                    event.getTicker()
            );
        }
        String pnlEmoji = event.getPnl() >= 0 ? "💰" : "\uD83E\uDDF1";

        return String.format(
                "🤖 *FundingBot:* Position Closed %s\n\n" +
                        "*ID:* `%s`\n" +
                        "*Mode:* %s\n" +
                        "*Ticker:* %s\n" +
                        "*Funding Rate:* %.2f%%\n" +
                        "*Reason:* %s\n" +
                        "*Exit Spread:* %s\n" +
                        "*P&L:* " + sign + "%.2f USD (%.2f%%)\n" +
                        "*API PnL:* " + sign + "%.2f USD (%.2f%%)\n",
                pnlEmoji,
                event.getPositionId(),
                event.getMode(),
                event.getTicker(),
                event.getRate(),
                event.getClosureReason(),
                getExitSpreadInfo(event.getData().getEntrySpreadPct(), event.getData().getExitSpreadPct()),
                event.getPnl(),
                event.getPercent(),
                event.getApiPnl(),
                event.getPercent()
        );
    }

    private String formatNotificationEvent(PositionNotificationEvent event) {
        return String.format(
                "🤖 *FundingBot:* Position `%s` Update \uD83D\uDCCC\n\n" +
                        "*Ticker:* %s\n" +
                        "*Notification:* %s\n" +
                        "*Message:* %s\n",
                event.getPositionId(),
                event.getTicker(),
                event.getHeader(),
                event.getMessage()
        );
    }

    private String formatLiveUpdate(PositionUpdateEvent event) {
        PositionPnLData pnl = event.getPnlData();
        Duration hold = Duration.between(pnl.getOpenTime(), LocalDateTime.now(ZoneOffset.UTC));

        double openSpread = pnl.getFirstSnapshot().getEntryPrice() > 0 && pnl.getSecondSnapshot().getEntryPrice() > 0
                ? Math.abs(pnl.getFirstSnapshot().getEntryPrice() - pnl.getSecondSnapshot().getEntryPrice())
                  / Math.min(pnl.getFirstSnapshot().getEntryPrice(), pnl.getSecondSnapshot().getEntryPrice()) * 100 : 0;
        double markSpread = pnl.getFirstSnapshot().getMarkPrice() > 0 && pnl.getSecondSnapshot().getMarkPrice() > 0
                ? Math.abs(pnl.getFirstSnapshot().getMarkPrice() - pnl.getSecondSnapshot().getMarkPrice())
                  / Math.min(pnl.getFirstSnapshot().getMarkPrice(), pnl.getSecondSnapshot().getMarkPrice()) * 100 : 0;

        String ex1 = ExchangeType.abbreviate(event.getEx1Name());
        String ex2 = ExchangeType.abbreviate(event.getEx2Name());

        double roi = event.getBalance() > 0 ? pnl.getNetPnl() / event.getBalance() * 100 : 0;
        String netSign = pnl.getNetPnl() >= 0 ? "+" : "";
        String fundSign = pnl.getTotalFundingNet() >= 0 ? "+" : "";
        String grossSign = pnl.getGrossPnl() >= 0 ? "+" : "";

        return String.format(
                "🤖 *[FundingBot]:* Position `%s` \uD83D\uDDFF\n\n" +
                        "\uD83D\uDCBC *Info:*\n" +
                        "Ticker: %s | Margin: %.2f$ \n" +
                        "Holdtime: %s | Rate: %.2f→%.2f\n" +
                        "7d APR: %.2f\n\n" +
                        "\uD83D\uDCCA *Position:*\n" +
                        "*%s:* %s→%s (Liq %s)\n" +
                        "*%s:* %s→%s (Liq %s)\n" +
                        "*Spread:* %.3f%%→%.3f%%\n\n" +
                        "\uD83D\uDCB0 *Profit*:\n" +
                        "*Gross PnL:* %s%.2f\n" +
                        "*Funding:* %s%.2f\n" +
                        "*Net PnL:* %s%.2f USD (%s%.1f%%)",
                pnl.getPositionId(),
                pnl.getTicker(), event.getBalance(),
                formatDuration(hold), event.getOpenFundingRate(), event.getCurrentFundingRate(),
                event.getHistoricalFundingRate(),

                ex1,
                formatPrice(pnl.getFirstSnapshot().getEntryPrice()), formatPrice(pnl.getFirstSnapshot().getMarkPrice()),
                formatPrice(pnl.getFirstSnapshot().getLiquidationPrice()),

                ex2,
                formatPrice(pnl.getSecondSnapshot().getEntryPrice()), formatPrice(pnl.getSecondSnapshot().getMarkPrice()),
                formatPrice(pnl.getSecondSnapshot().getLiquidationPrice()),

                openSpread, markSpread,
                grossSign, pnl.getGrossPnl(),
                fundSign, pnl.getTotalFundingNet(),
                netSign, pnl.getNetPnl(), netSign, roi
        );
    }

    private String formatPrice(double price) {
        if (price >= 1000) return String.format("%.1f", price);
        if (price >= 1) return String.format("%.2f", price);
        if (price >= 0.01) return String.format("%.4f", price);
        return String.format("%.6f", price);
    }

    private void getTrades(Long chatId) {
        log.info("[Telegram] Got trades history request");

        String message = formatBalanceMap(
                exchangesService.getTrades(),
                exchangesService.getOpenedPositions()
        );

        sendMessage(chatId, message);
    }

    public String formatBalanceMap(Map<String, PositionBalance> balanceMap,
                                   Map<String, FundingCloseSignal> openedPositions) {
        if (balanceMap.isEmpty()) {
            return "🤖 *FundingBot:* Balance Tracker\n\n_No positions tracked yet_";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 *FundingBot:* Positions History\n");
        sb.append("━━━━━━━━━━━━━━━━━━\n");

        double totalPnL = 0.0;
        int wins = 0;
        int losses = 0;

        //Sorting by position id
        List<Map.Entry<String, PositionBalance>> sortedEntries = balanceMap.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> {
                    try {
                        return Integer.parseInt(e.getKey().replaceAll("\\D+", ""));
                    } catch (NumberFormatException ex) {
                        return Integer.MAX_VALUE;
                    }
                }))
                .toList();

        for (Map.Entry<String, PositionBalance> entry : sortedEntries) {
            String positionId = entry.getKey();
            PositionBalance balance = entry.getValue();

            if (balance.isClosed()) {
                double pnl = balance.getProfit();
                totalPnL += pnl;

                String emoji = pnl > 0 ? "💰" : "\uD83E\uDDF1";
                String sign = pnl > 0 ? "+" : "";

                if (pnl > 0) wins++;
                else losses++;

                sb.append(String.format(
                        "`%s` %s %s$%.2f\n",
                        positionId,
                        emoji,
                        sign,
                        pnl
                ));
            } else {
                FundingCloseSignal position = openedPositions.get(positionId);
                String emoji = (position != null && position.getMode() == HoldingMode.FAST_MODE)
                        ? "⚡"
                        : "🧠";

                sb.append(String.format(
                        "`%s` %s _Pending_\n",
                        positionId,
                        emoji
                ));
            }
        }

        //Summary
        if (wins + losses > 0) {
            String summaryEmoji = totalPnL >= 0 ? "🟢" : "🔴";
            String sign = totalPnL >= 0 ? "+" : "";
            double winRate = (double) wins / (wins + losses) * 100;

            sb.append("━━━━━━━━━━━━━━━━━━\n");
            sb.append(String.format(
                    "%s *Total:* %s$%.2f\n" +
                            "📊 *Win Rate:* %d/%d (%.1f%%)\n",
                    summaryEmoji,
                    sign,
                    totalPnL,
                    wins,
                    wins + losses,
                    winRate
            ));
        }

        return sb.toString();
    }

    private String formatAlert(ArbitrageRates rate) {
        return String.format("🚨 *High Arbitrage Alert* 🚨\n\n" +
                        "*Symbol: %s*\n" +
                        "*Funding Rate:* %.2f%%\n" +
                        "*%s:* %.2f%%\n" +
                        "*%s:* %.2f%%\n" +
                        "*Action:* %s",
                rate.getSymbol(),
                rate.getArbitrageRate(),
                rate.getFirstExchange().getDisplayName(),
                rate.getFirstRate(),
                rate.getSecondExchange().getDisplayName(),
                rate.getSecondRate(),
                rate.getAction());
    }

    public String validateCurrentPnl(PositionPnLData pnlData) {
        if (pnlData == null) {
            return "Failed to calculate P&L";
        }

        Duration duration = Duration.between(
                pnlData.getOpenTime(),
                LocalDateTime.now(ZoneOffset.UTC)
        );

        long heldHours = duration.toHours();
        long heldMinutes = duration.toMinutesPart();

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 *FundingBot:* Profit Alert 🎯\n\n");
        sb.append("*ID:* ").append("`").append(pnlData.getPositionId()).append("`").append("\n");
        sb.append("*Ticker:* ").append(pnlData.getTicker()).append("\n");
        sb.append("*Hold time:* ");
        if (heldHours > 0) {
            sb.append(heldHours).append("h ");
        }
        sb.append(heldMinutes).append("m\n\n");

        sb.append("*Gross P&L:* ").append(formatMoney(pnlData.getGrossPnl())).append("\n");
        sb.append("*Funding:* ").append(formatMoney(pnlData.getTotalFundingNet())).append("\n");

        double netPnl = pnlData.getNetPnl();
        String sign = netPnl >= 0 ? "+" : "";
        sb.append("*Net P&L:* ").append(sign).append(String.format("%.4f USD", netPnl));

        return sb.toString();
    }

    private String formatMoney(double amount) {
        return String.format("%+.4f USD", amount);
    }

    private String formatPnLThresholdMessage(PnLThresholdEvent event) {
        PositionPnLData pnl = event.getPnlData();

        return String.format(
                "🤖 *FundingBot:* P&L Alert \uD83D\uDCB0\n\n" +
                        "*ID:* `%s`\n" +
                        "*Ticker:* %s\n" +
                        "*ROI:* %.2f%%\n" +
                        "*Net:* $%.2f",
                event.getPositionId(),
                event.getTicker(),
                event.getThresholdPercent(),
                pnl.getNetPnl()
        );
    }

    private List<InlineKeyboardButton> createButtonRow(String text1, String callback1, String text2, String callback2) {
        List<InlineKeyboardButton> row = new ArrayList<>();

        InlineKeyboardButton btn1 = new InlineKeyboardButton();
        btn1.setText(text1);
        btn1.setCallbackData(callback1);
        row.add(btn1);

        if (text2 != null && callback2 != null) {
            InlineKeyboardButton btn2 = new InlineKeyboardButton();
            btn2.setText(text2);
            btn2.setCallbackData(callback2);
            row.add(btn2);
        }

        return row;
    }

    /**
     * History
     */
    private void getTradeHistory(Long chatId) {
        log.info("[Telegram] Trade history request from chat {}", chatId);
        sendTypingAction(chatId);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("🤖 *FundingBot:* Choose period:");
        message.setParseMode("Markdown");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(createButtonRow("📅 Today", "history:DAY", "📅 Week", "history:WEEK"));
        rows.add(createButtonRow("📅 Month", "history:MONTH", "📋 All", "history:ALL"));
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("[Telegram] Failed to send history menu", e);
        }
    }

    private void handleHistoryCallback(Long chatId, String periodStr) {
        try {
            Period period = Period.valueOf(periodStr);
            sendMessage(chatId, formatSinglePeriod(period));
        } catch (IllegalArgumentException e) {
            sendMessage(chatId, "❌ Unknown period: " + periodStr);
        }
    }

    private String formatSinglePeriod(Period period) {
        TradeHistory stats = tradeHistoryService.getStats(period);

        String label = switch (period) {
            case DAY -> "Today";
            case WEEK -> "7 Days";
            case MONTH -> "30 Days";
            case ALL -> "All Time";
        };

        if (stats.getTotalTrades() == 0) {
            return String.format("🤖 *FundingBot:* Trade History\n\n📅 *%s* — _No trades_", label);
        }

        return String.format("""
                        🤖 *FundingBot:* Trade History 📈
                        ━━━━━━━━━━━━━━━━━
                        📅 *%s*  |  *%d Orders*  |  💼 *%.2f$ Volume*
                        
                        💰 *P&L:*
                        *Total:* %s%.2f$ / %s%.2f%% ROI
                        *Avg/trade:* %s%.2f$
                        *Funding:* %s%.2f$ (%.1f%% from P&L)
                        
                        🎯 *Stats:*
                        *Win Rate:* %d/%d (%.1f%%)
                        *Best:* +%.2f$ / Worst: %.2f$
                        
                        📦 *Funding:*
                        *Avg:* %.2f%% → %.2f%% / *Delta:* %s%.2f%%
                        (%s)
                        
                        ⌛️ *Hold time:*
                        *Avg:* %s / *Max:* %s %s / *Min:* %s %s
                        
                        🪙 *Tickers:*
                        %s""",
                //header
                label, stats.getTotalTrades(), stats.getTotalVolume(),
                // P&L
                stats.getTotalPnl() >= 0 ? "+" : "", stats.getTotalPnl(),
                stats.getPnlToVolumePercent() >= 0 ? "+" : "", stats.getPnlToVolumePercent(),
                stats.getAvgPnlPerTrade() >= 0 ? "+" : "", stats.getAvgPnlPerTrade(),
                stats.getTotalFunding() >= 0 ? "+" : "", stats.getTotalFunding(),
                stats.getFundingToPnlPercent(),
                //Stats
                stats.getWins(), stats.getTotalTrades(), stats.getWinRate(),
                stats.getBestTrade(), stats.getWorstTrade(),
                //Funding
                stats.getAvgOpenRate(), stats.getAvgCloseRate(),
                stats.getAvgRateDelta() >= 0 ? "+" : "", stats.getAvgRateDelta(),
                stats.getAvgRateDelta() < 0
                        ? "Closing after rate is down"
                        : "Closing after rate is up",
                //Hold time
                formatDuration(stats.getAvgHoldTime()),
                stats.getMaxHoldTicker(), formatDuration(stats.getMaxHoldTime()),
                stats.getMinHoldTicker(), formatDuration(stats.getMinHoldTime()),
                //Tickers
                formatTickerStats(stats.getTickerStats())
        );
    }

    private String formatDuration(Duration d) {
        if (d == null) return "—";
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private String formatTickerStats(List<TickerStats> tickers) {
        if (tickers == null || tickers.isEmpty()) return "  _no data_";
        StringBuilder sb = new StringBuilder();
        for (TickerStats t : tickers) {
            sb.append(String.format("*%s:* %s$%.2f / %d Orders / Winrate %.0f%%%n",
                    t.getTicker(),
                    t.getTotalPnl() >= 0 ? "+" : "",
                    t.getTotalPnl(),
                    t.getTradeCount(),
                    t.getWinRate()));
        }
        return sb.toString().stripTrailing();
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String callbackId = callbackQuery.getId();
        String data = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();

        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackId);
        answer.setShowAlert(false);
        try {
            execute(answer);
        } catch (TelegramApiException e) {
            log.error("[Telegram] Error answering callback", e);
        }

        if (data.startsWith("history:")) {
            handleHistoryCallback(chatId, data.substring(8));
        }
    }

    private String getEntrySpreadInfo(PositionPriceSnapshot firstPos, PositionPriceSnapshot secondPos, double spread) {
        return String.format(
                "%s: %s | %s: %s | Spread: %.2f%%",
                ExchangeType.abbreviate(firstPos.getExchangeType().getDisplayName()), formatPrice(firstPos.getEntryPrice()),
                ExchangeType.abbreviate(secondPos.getExchangeType().getDisplayName()), formatPrice(secondPos.getEntryPrice()),
                spread
        );
    }

    private String getExitSpreadInfo(double entrySpread, double exitSpread) {
        return String.format(
                "%.2f%% → %.2f%%",
                entrySpread,
                exitSpread
        );
    }

    public void sendMessageAndScheduleDelete(Long chatId, String text, long delayMinutes) {
        sendTypingAction(chatId);
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("Markdown");
        try {
            Integer messageId = execute(message).getMessageId();
            deleteScheduler.schedule(() -> deleteMessage(chatId, messageId),
                    delayMinutes, TimeUnit.MINUTES);
        } catch (TelegramApiException e) {
            log.error("Telegram Failed to send message to chat {}", chatId, e);
        }
    }

    private void scheduleDelete(Long chatId, Integer messageId, long delayMinutes) {
        deleteScheduler.schedule(
                () -> deleteMessage(chatId, messageId),
                delayMinutes,
                TimeUnit.MINUTES
        );
    }

    private void deleteMessage(Long chatId, Integer messageId) {
        try {
            DeleteMessage delete = new DeleteMessage();
            delete.setChatId(chatId);
            delete.setMessageId(messageId);
            execute(delete);
            log.info("Telegram Deleted message {} in chat {}", messageId, chatId);
        } catch (TelegramApiException e) {
            log.warn("Telegram Failed to delete message {}: {}", messageId, e.getMessage());
        }
    }

    //Sub
    private void addSubscriberId(Long chatId) {
        fundingContext.addSubscriberId(chatId);
        sendMessageAndScheduleDelete(chatId, "🤖 *FundingBot:* User `" + chatId + "` added", 3);
    }

    //Blacklist
    private void addToBlacklist(Long chatId, String[] parts) {
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            showBlacklist(chatId);
            return;
        }
        String ticker = parts[1].trim().toUpperCase();
        fundingContext.addToBlacklist(ticker);
        sendMessageAndScheduleDelete(chatId, "🤖 *FundingBot:* Added to blacklist: " + ticker, 3);
    }

    private void showBlacklist(Long chatId) {
        Set<String> list = fundingContext.getTickerBlacklist();
        String msg = list.isEmpty()
                ? "🤖 *FundingBot:* Blacklist is empty"
                : "🤖 *FundingBot:* Blacklisted tickers:\n" + String.join(", ", list);
        sendMessageAndScheduleDelete(chatId, msg, 3);
    }

    private void removeFromBlacklist(Long chatId, String[] parts) {
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            sendMessage(chatId, "Usage: /blacklist_remove TICKER");
            return;
        }
        String ticker = parts[1].trim().toUpperCase();
        fundingContext.removeFromBlacklist(ticker);
        sendMessageAndScheduleDelete(chatId, "🤖 *FundingBot:* Removed from blacklist: " + ticker, 3);
    }

    private void disableExchange(Long chatId, String[] parts) {
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            sendMessage(chatId, "Usage: /exchange_disable NAME");
            return;
        }
        String exchange = parts[1].trim().toUpperCase();
        fundingContext.disableExchange(exchange);
        sendMessageAndScheduleDelete(chatId, "🤖 *FundingBot:* Exchange " + exchange + " disabled: ", 2);
    }

    private void enableExchange(Long chatId, String[] parts) {
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            sendMessage(chatId, "Usage: /exchange_enaable NAME");
            return;
        }
        String exchange = parts[1].trim().toUpperCase();
        fundingContext.enableExchange(exchange);
        sendMessageAndScheduleDelete(chatId, "🤖 *FundingBot:* Exchange " + exchange + " enabled: ", 2);
    }

    private void showExchanges(Long chatId) {
        sendMessageAndScheduleDelete(chatId, "🤖 *FundingBot:* Enabled exchanges " + fundingContext.getDisabledExchanges(), 2);
    }
}
