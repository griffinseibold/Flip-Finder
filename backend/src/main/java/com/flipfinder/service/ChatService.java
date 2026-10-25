package com.flipfinder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipfinder.config.ChatEnabledCondition;
import com.flipfinder.dto.AccountDto;
import com.flipfinder.dto.ChatRequest;
import com.flipfinder.dto.FlipDto;
import com.flipfinder.dto.FlipPageResponse;
import com.flipfinder.dto.ItemHistoryDto;
import com.flipfinder.service.FlipQuery.Membership;
import com.flipfinder.service.FlipQuery.PriceBasis;
import com.flipfinder.service.FlipQuery.Sort;
import com.flipfinder.service.LlmClient.Health;
import com.flipfinder.service.LlmClient.Reply;
import com.flipfinder.service.LlmClient.StartingException;
import com.flipfinder.service.LlmClient.ToolCall;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

/**
 * Answers a player's questions with the homelab's language model. The model
 * looks up flips through a find_flips tool backed by {@link FlipService}, so
 * its numbers come from the same prices as the rest of Flip Finder, and, when
 * history is kept, checks whether trading is typical with an item_history tool.
 *
 * <p>The homelab runs its model only while an application asks for it, so
 * right after Flip Finder starts the model may still be starting. A question
 * asked then waits for it, telling the player why, instead of failing.
 */
@Service
@Conditional(ChatEnabledCondition.class)
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    // After this many rounds of tool calls the model has to answer.
    static final int MAX_TOOL_ROUNDS = 3;
    // Older messages are dropped to keep the prompt within the model's context.
    static final int MAX_HISTORY = 12;
    // Matches the prompt's three to five suggestions; the flips table has the rest.
    static final int FLIPS_PER_LOOKUP = 5;

    // The flips table's defaults: items trading actively enough to be priced reliably.
    private static final int ACTIVE_WITHIN_MINUTES = 60;
    private static final long ACTIVE_MIN_VOLUME_5M = 10;

    static final String STARTING_MESSAGE = "The homelab's language model is starting. This can take a minute.";
    static final String STILL_STARTING_MESSAGE =
            "The homelab's language model is still starting. Try again in a minute.";
    static final String UNREACHABLE_MESSAGE = "The homelab's language model could not answer. Is it running?";

    /** Pauses between readiness checks. Tests replace it so they need not really wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final Map<String, Object> FIND_FLIPS = Map.of(
            "type", "function",
            "function", Map.of(
                    "name", "find_flips",
                    "description", "Rank Grand Exchange flips for the player, using their account's coins, "
                            + "membership and buy limits left. Returns prices, margins and estimated profit.",
                    "parameters", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "search", Map.of("type", "string",
                                            "description", "Part of an item name, to look up specific items"),
                                    "budget", Map.of("type", "integer",
                                            "description", "Coins to flip with, when the player asks about "
                                                    + "an amount other than their own"),
                                    "sort", Map.of("type", "string",
                                            "enum", List.of("estimatedProfit", "roi", "margin", "volume"),
                                            "description", "How to rank the flips; estimatedProfit by default"),
                                    "membership", Map.of("type", "string",
                                            "enum", List.of("all", "f2p", "members"),
                                            "description", "Only free-to-play or members items")),
                            "required", List.of())));

    static final Map<String, Object> ITEM_HISTORY = Map.of(
            "type", "function",
            "function", Map.of(
                    "name", "item_history",
                    "description", "Compare an item's latest trading with its recent history: whether its "
                            + "five-minute volume, price and margin are typical or unusual, and how its price "
                            + "has moved over the last day, week and month.",
                    "parameters", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "item", Map.of("type", "string", "description", "The item's name")),
                            "required", List.of("item"))));

    private final LlmClient llm;
    private final FlipService flips;
    private final AccountService accounts;
    private final Optional<PriceHistoryService> history;
    private final ObjectMapper mapper;
    private final List<Map<String, Object>> tools;
    private final Duration startupWait;
    private final Duration startupPoll;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public ChatService(LlmClient llm, FlipService flips, AccountService accounts,
            Optional<PriceHistoryService> history, ObjectMapper mapper,
            @Value("${flipfinder.llm.startup-wait:90s}") Duration startupWait,
            @Value("${flipfinder.llm.startup-poll:2s}") Duration startupPoll) {
        this(llm, flips, accounts, history, mapper, startupWait, startupPoll, Clock.systemUTC(), Thread::sleep);
    }

    ChatService(LlmClient llm, FlipService flips, AccountService accounts,
            Optional<PriceHistoryService> history, ObjectMapper mapper,
            Duration startupWait, Duration startupPoll, Clock clock, Sleeper sleeper) {
        this.llm = llm;
        this.flips = flips;
        this.accounts = accounts;
        this.history = history;
        this.mapper = mapper;
        this.tools = history.isPresent() ? List.of(FIND_FLIPS, ITEM_HISTORY) : List.of(FIND_FLIPS);
        this.startupWait = startupWait;
        this.startupPoll = startupPoll;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Answers the last message, emitting text, tool activity and the flips looked up as they happen. */
    public void chat(ChatRequest request, Consumer<Map<String, Object>> emit) {
        long now = Instant.now().getEpochSecond();
        Optional<AccountDto> account = request.account == null
                ? Optional.empty()
                : accounts.find(request.account, now);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(LlmClient.message("system", systemPrompt(account.orElse(null), now, history.isPresent())));
        List<ChatRequest.Message> history = request.messages.stream()
                .filter(message -> ("user".equals(message.role) || "assistant".equals(message.role))
                        && message.content != null && !message.content.isBlank())
                .toList();
        for (ChatRequest.Message message : history.subList(Math.max(history.size() - MAX_HISTORY, 0), history.size())) {
            messages.add(LlmClient.message(message.role, message.content));
        }

        try {
            Startup startup = new Startup();
            // Checking first means the player hears why the answer is slow as
            // soon as they ask, rather than after a connection attempt fails.
            if (llm.health() == Health.STARTING) {
                awaitStart(startup, emit);
            }
            for (int round = 0; ; round++) {
                List<Map<String, Object>> offered = round < MAX_TOOL_ROUNDS ? tools : List.of();
                Reply reply = complete(messages, offered, startup, emit);
                List<ToolCall> calls = reply.toolCalls().stream().filter(call -> call.name() != null).toList();
                if (calls.isEmpty()) {
                    break;
                }
                messages.add(LlmClient.assistantToolCalls(reply.content(), calls));
                for (ToolCall call : calls) {
                    messages.add(LlmClient.toolResult(call.id(), runTool(call, account.orElse(null), emit)));
                }
            }
        } catch (StartingException e) {
            logger.warn("Chat gave up waiting for the language model to start: {}", e.getMessage());
            emit.accept(event("error", "message", STILL_STARTING_MESSAGE));
        } catch (IOException e) {
            logger.warn("Chat failed: {}", e.getMessage());
            emit.accept(event("error", "message", UNREACHABLE_MESSAGE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emit.accept(event("error", "message", "The answer was interrupted."));
        }
        emit.accept(Map.of("type", "done"));
    }

    /** Whether this answer has already waited for the model to start. */
    private static final class Startup {
        boolean waited;
    }

    /**
     * Asks the model, waiting for it once if it turns out to be starting. The
     * health check can miss a start: the model may stop between the check and
     * the question, or a server outside the cluster may have no health check.
     * Nothing has been streamed for a request refused this way, so asking
     * again cannot repeat any of the answer.
     */
    private Reply complete(List<Map<String, Object>> messages, List<Map<String, Object>> offered, Startup startup,
            Consumer<Map<String, Object>> emit) throws IOException, InterruptedException {
        Consumer<String> onText = text -> emit.accept(event("text", "text", text));
        try {
            return llm.complete(messages, offered, onText);
        } catch (StartingException e) {
            if (startup.waited) {
                throw e;
            }
            awaitStart(startup, emit);
            return llm.complete(messages, offered, onText);
        }
    }

    /**
     * Tells the player the model is starting, then checks every startupPoll
     * until it is ready or startupWait has passed. The wait stays well inside
     * spring.mvc.async.request-timeout, so the answer still has time to stream
     * once the model is up. A server whose health check stops saying
     * "starting" without saying "ready" is asked anyway, so it fails or
     * answers as it would have without the wait.
     *
     * @throws StartingException when the model is still starting at the end
     */
    private void awaitStart(Startup startup, Consumer<Map<String, Object>> emit)
            throws StartingException, InterruptedException {
        startup.waited = true;
        logger.info("The language model is starting; waiting up to {}", startupWait);
        emit.accept(event("status", "message", STARTING_MESSAGE));
        Instant started = clock.instant();
        Instant deadline = started.plus(startupWait);
        while (clock.instant().isBefore(deadline)) {
            sleeper.sleep(startupPoll);
            if (llm.health() != Health.STARTING) {
                logger.info("The language model was ready after {}s",
                        Duration.between(started, clock.instant()).toSeconds());
                return;
            }
        }
        throw new StartingException("The language model was still starting after " + startupWait, null);
    }

    private String runTool(ToolCall call, AccountDto account, Consumer<Map<String, Object>> emit) {
        JsonNode arguments;
        try {
            arguments = mapper.readTree(call.arguments());
        } catch (JsonProcessingException e) {
            return json(Map.of("error", "The arguments were not valid JSON"));
        }
        if ("find_flips".equals(call.name())) {
            return findFlips(arguments, account, emit);
        }
        if ("item_history".equals(call.name()) && history.isPresent()) {
            return itemHistory(arguments, history.get(), emit);
        }
        return json(Map.of("error", "Unknown tool " + call.name()));
    }

    private String findFlips(JsonNode arguments, AccountDto account, Consumer<Map<String, Object>> emit) {
        String search = arguments.path("search").asText("").trim();
        Long budget = arguments.path("budget").asLong(0) > 0 ? arguments.get("budget").asLong() : null;
        Sort sort = switch (arguments.path("sort").asText("")) {
            case "roi" -> Sort.ROI;
            case "margin" -> Sort.MARGIN;
            case "volume" -> Sort.VOLUME_5M;
            default -> Sort.ESTIMATED_PROFIT;
        };
        Membership membership = switch (arguments.path("membership").asText("")) {
            case "f2p" -> Membership.F2P;
            case "members" -> Membership.MEMBERS;
            default -> Membership.ALL;
        };
        emit.accept(toolStarted("find_flips", search, budget));

        // A named item is wanted however quietly it trades.
        boolean activeOnly = search.isEmpty();
        FlipQuery query = new FlipQuery(PriceBasis.LATEST, search, membership,
                activeOnly ? ACTIVE_WITHIN_MINUTES : 0, activeOnly ? ACTIVE_MIN_VOLUME_5M : 0,
                budget, sort, null, account == null ? null : account.accountHash);
        // A search looks further than the top few, so an item named exactly can be found among
        // many matches; it is then the only one shown, since the player asked about it.
        FlipPageResponse page = flips.find(query, 0, search.isEmpty() ? FLIPS_PER_LOOKUP : FlipService.MAX_PAGE_SIZE);
        List<FlipDto> exact = page.items.stream().filter(flip -> flip.item.name.equalsIgnoreCase(search)).toList();
        List<FlipDto> shown = exact.isEmpty()
                ? page.items.subList(0, Math.min(FLIPS_PER_LOOKUP, page.items.size()))
                : exact;

        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("type", "flips");
        lookup.put("search", search);
        lookup.put("budget", page.budget);
        lookup.put("sort", sort.value());
        lookup.put("total", page.total);
        lookup.put("items", shown);
        emit.accept(lookup);

        long now = Instant.now().getEpochSecond();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("budget", coins(page.budget));
        result.put("matchingItems", page.total);
        List<Map<String, Object>> flips = new ArrayList<>();
        for (FlipDto flip : shown) {
            flips.add(describe(flips.size() + 1, flip, now));
        }
        result.put("flips", flips);
        return json(result);
    }

    private String itemHistory(JsonNode arguments, PriceHistoryService history, Consumer<Map<String, Object>> emit) {
        String name = arguments.path("item").asText("").trim();
        emit.accept(toolStarted("item_history", name, null));
        Optional<PriceHistoryService.Match> match = history.find(name);

        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("type", "history");
        lookup.put("search", name);
        if (match.isEmpty()) {
            lookup.put("history", null);
            emit.accept(lookup);
            return json(Map.of("error", "No item is called \"" + name + "\""));
        }
        ItemHistoryDto summary = history.summarize(match.get().item(), Instant.now().getEpochSecond());
        lookup.put("history", summary);
        emit.accept(lookup);

        // The notes already hold the comparison in words, so the model need not work anything out.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item", summary.name);
        if (!match.get().others().isEmpty()) {
            result.put("otherItemsMatchingTheName", match.get().others());
        }
        result.put("volume", summary.volume.verdict);
        result.put("margin", summary.margin.verdict);
        result.put("notes", summary.notes);
        return json(result);
    }

    /**
     * The flip as a finished line of the answer. Copying it is more reliable
     * than composing it: a small model left alone drops digits from long
     * prices and attaches warnings to the wrong flips.
     */
    static String line(FlipDto flip) {
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "**%s**: buy %s, sell %s, %s each",
                flip.item.name, coins(flip.buyPrice), coins(flip.sellPrice), coins(flip.margin)));
        if (flip.estimatedProfit == null) {
            line.append(", buy limit unknown");
        } else if (flip.estimatedProfit == 0 && flip.limitedBy == FlipDto.LimitedBy.VOLUME) {
            line.append(", but one side has not traded in the latest five minutes");
        } else {
            line.append(", about ").append(compact(flip.estimatedProfit)).append(" profit");
            if (flip.limitedBy != null) {
                line.append(switch (flip.limitedBy) {
                    case BUY_LIMIT -> ", capped by the buy limit";
                    case BUDGET -> ", capped by the budget";
                    case VOLUME -> ", capped by trading volume";
                });
            }
        }
        if (flip.warning != null) {
            line.append(" (").append(flip.warning).append(')');
        }
        return line.toString();
    }

    private static String compact(long coins) {
        long size = Math.abs(coins);
        if (size >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", coins / 1_000_000.0);
        }
        return size >= 10_000 ? String.format(Locale.ROOT, "%.0fK", coins / 1_000.0) : coins(coins);
    }

    private static Map<String, Object> toolStarted(String name, String search, Long budget) {
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("type", "tool");
        started.put("name", name);
        started.put("search", search);
        started.put("budget", budget);
        return started;
    }

    /**
     * A flip as the model sees it, under self-explanatory names. Coins arrive
     * already written out, because small models drop digits when they reformat
     * a long raw number such as 12152033.
     */
    private static Map<String, Object> describe(int rank, FlipDto flip, long now) {
        Map<String, Object> row = new LinkedHashMap<>();
        // Small models reorder a list unless each row says where it belongs.
        row.put("rank", rank);
        row.put("item", flip.item.name);
        row.put("line", line(flip));
        row.put("members", flip.item.members);
        row.put("buyAt", coins(flip.buyPrice));
        row.put("sellAt", coins(flip.sellPrice));
        row.put("tax", coins(flip.tax));
        row.put("marginPerItem", coins(flip.margin));
        row.put("roiPercent", flip.roi == null ? null : Math.round(flip.roi * 1000) / 10.0);
        row.put("buyLimit", flip.item.buyLimit);
        row.put("alreadyBought", flip.alreadyBought);
        row.put("limitResetsInMinutes", flip.limitResetsAt == null ? null : Math.max((flip.limitResetsAt - now) / 60, 0));
        row.put("quantity", flip.quantity);
        row.put("fillableIn4h", flip.fillableQuantity);
        row.put("estimatedProfit4h", coins(flip.estimatedProfit));
        row.put("cappedBy", flip.limitedBy == null ? null : flip.limitedBy.value());
        row.put("traded5mAtLowPrice", flip.item.lowPriceVolume5m);
        row.put("traded5mAtHighPrice", flip.item.highPriceVolume5m);
        row.put("lastTradeMinutesAgo", flip.lastTradeTime == null ? null : (now - flip.lastTradeTime) / 60);
        row.put("volumeVsUsual", flip.volumeVsUsual);
        return row;
    }

    static String systemPrompt(AccountDto account, long now, boolean history) {
        StringBuilder prompt = new StringBuilder("""
                You are Flip Finder, an assistant that helps an Old School RuneScape player choose Grand \
                Exchange flips: items to buy and resell for a profit.

                How Flip Finder prices a flip:
                - Buy at the latest instant-sell (low) price and sell at the latest instant-buy (high) price.
                - Grand Exchange tax: the seller pays 2% of the sale price, rounded down and capped at \
                5,000,000 coins per item. Sales under 50 coins pay none, and a few items such as bonds are exempt.
                - Margin per item = sell price - tax - buy price.
                - Each item can only be bought a limited number of times every four hours, counted from the \
                first purchase.
                - Estimated profit = margin x how many could fill in four hours at the latest five-minute \
                trading pace, capped by the buy limit left and the budget. It is an upper bound: other \
                players compete for the same trades.

                Rules:
                - Get prices, margins and flip ideas only from the tools. Never make up items or numbers.
                - find_flips ranks the best flips first. Suggest the top three to five in rank order \
                unless the player asks for more. When the player asks about one item, show only that item.
                - Each flip has a line. Show a flip by copying its line exactly after its number, like \
                "1. " followed by the line. Never rewrite the numbers in a line.
                - Answer briefly, in plain language, and write coins like 1.2M or 45,000 gp.
                - If an item trades slowly or last traded long ago, warn that its price may be unreliable.

                """);
        if (history) {
            prompt.append("""
                    Trading history:
                    - Use item_history when the player asks whether an item's volume, price or margin is \
                    normal, how its price has moved, or whether a flip is safe. Pass on its notes in plain \
                    words without recalculating them.
                    - When the player asks whether an item is a good flip, look it up with both find_flips \
                    and item_history: give its line, then say whether its trading looks normal.
                    - A flip line ending in brackets, like "(volume 4x usual, may not last)", is trading far \
                    from normal. Keep the brackets when you copy it.

                    """);
        }
        if (account == null) {
            prompt.append("""
                    The player has not connected the Flip Finder RuneLite plugin, so their coins, membership \
                    and buy limits are unknown. If they ask about their own account, explain that the plugin \
                    reports it, and ask for their budget when it matters.
                    """);
            return prompt.toString();
        }

        prompt.append("The player's account, as the RuneLite plugin last reported it")
                .append(ago(account.capturedAt, now)).append(":\n");
        prompt.append("- Name: ").append(account.displayName == null ? "unknown" : account.displayName).append('\n');
        if (account.ironman) {
            prompt.append("- Ironman: cannot use the Grand Exchange at all, so flipping is impossible.\n");
        }
        prompt.append(account.members
                ? "- Member, with " + account.membershipDays + " days left.\n"
                : "- Free-to-play: can only trade free-to-play items.\n");
        if (account.coins != null) {
            prompt.append("- Coins: ").append(coins(account.coins)).append(" (").append(coins(account.inventoryCoins))
                    .append(" carried, ").append(coins(account.bankCoins)).append(" in the bank").append(')')
                    .append('\n');
        } else {
            prompt.append("- Coins: ").append(coins(account.inventoryCoins))
                    .append(" carried; the bank has not been seen, so the total is unknown.\n");
        }
        if (account.geOffers.isEmpty()) {
            prompt.append("- No Grand Exchange offers.\n");
        } else {
            prompt.append("- Grand Exchange offers:\n");
            for (AccountDto.Offer offer : account.geOffers) {
                prompt.append("  - ").append(offer.state.toLowerCase(Locale.ROOT).replace('_', ' ')).append(' ')
                        .append(offer.name == null ? "item " + offer.itemId : offer.name).append(": ")
                        .append(String.format("%,d of %,d", offer.quantityTraded, offer.totalQuantity))
                        .append(" at ").append(coins((long) offer.price)).append(" each\n");
            }
        }
        if (!account.buyLimits.isEmpty()) {
            prompt.append("- Buy limits in use:\n");
            for (AccountDto.BuyLimitUse use : account.buyLimits) {
                prompt.append("  - ").append(use.name == null ? "item " + use.itemId : use.name).append(": ")
                        .append(String.format("%,d", use.bought)).append(" bought")
                        .append(use.limit == null ? "" : String.format(" of %,d", use.limit))
                        .append(", resets in ").append(duration(use.resetsAt - now)).append('\n');
            }
        }
        prompt.append("find_flips already uses these coins as the budget, this membership and the buy limits left, "
                + "unless you pass another budget.\n");
        return prompt.toString();
    }

    private static String coins(Long amount) {
        return amount == null ? null : String.format(Locale.ROOT, "%,d gp", amount);
    }

    private static String ago(long timestamp, long now) {
        long minutes = Math.max((now - timestamp) / 60, 0);
        return minutes < 1 ? " just now" : " " + duration(now - timestamp) + " ago";
    }

    static String duration(long seconds) {
        long minutes = Math.max(seconds, 0) / 60;
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    private static Map<String, Object> event(String type, String key, Object value) {
        return Map.of("type", type, key, value);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
