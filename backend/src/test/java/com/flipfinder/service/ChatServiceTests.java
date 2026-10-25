package com.flipfinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipfinder.dto.AccountDto;
import com.flipfinder.dto.ChatRequest;
import com.flipfinder.dto.FlipDto;
import com.flipfinder.dto.FlipPageResponse;
import com.flipfinder.dto.ItemDto;
import com.flipfinder.dto.ItemHistoryDto;
import com.flipfinder.service.FlipQuery.PriceBasis;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

class ChatServiceTests {
    private static final Duration STARTUP_WAIT = Duration.ofSeconds(90);
    private static final Duration STARTUP_POLL = Duration.ofSeconds(2);

    private final LlmClient llm = mock(LlmClient.class);
    private final FlipService flips = mock(FlipService.class);
    private final AccountService accounts = mock(AccountService.class);
    private final FakeTime time = new FakeTime();
    private final ChatService chat = chat(Optional.empty());
    private final List<Map<String, Object>> events = new ArrayList<>();

    /**
     * A clock that only moves when the chat sleeps, so waiting for the model
     * to start takes no real time and the waits can be counted.
     */
    private static final class FakeTime extends Clock implements ChatService.Sleeper {
        private Instant now = Instant.parse("2026-10-24T12:00:00Z");
        private final List<Duration> sleeps = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private ChatService chat(Optional<PriceHistoryService> history) {
        return new ChatService(llm, flips, accounts, history, new ObjectMapper(), STARTUP_WAIT, STARTUP_POLL,
                time, time);
    }

    @BeforeEach
    void modelIsReady() throws Exception {
        when(llm.health()).thenReturn(LlmClient.Health.READY);
    }

    private static LlmClient.Reply answer(String text) {
        return new LlmClient.Reply(text, List.of(), "stop");
    }

    /** Streams the text as the model would, then returns it as the whole reply. */
    private static Answer<LlmClient.Reply> streams(String text) {
        return invocation -> {
            invocation.<Consumer<String>>getArgument(2).accept(text);
            return answer(text);
        };
    }

    private static ChatRequest ask(Long account, String question) {
        ChatRequest request = new ChatRequest();
        request.account = account;
        ChatRequest.Message message = new ChatRequest.Message();
        message.role = "user";
        message.content = question;
        request.messages = List.of(message);
        return request;
    }

    private static FlipPageResponse goldLeafPage() {
        FlipDto flip = FlipCalculator.calculate(FlipCalculatorTests.item(), PriceBasis.LATEST, 9_001_000L);
        FlipPageResponse page = new FlipPageResponse();
        page.items = List.of(flip);
        page.total = 1;
        page.budget = 9_001_000L;
        return page;
    }

    private static AccountDto account() {
        AccountDto account = new AccountDto();
        account.accountHash = 42;
        account.displayName = "Flipper";
        account.members = true;
        account.membershipDays = 30;
        account.inventoryCoins = 1_000;
        account.bankCoins = 9_000_000L;
        account.coins = 9_001_000L;
        account.capturedAt = Instant.now().getEpochSecond() - 120;
        account.geOffers = List.of();
        AccountDto.BuyLimitUse use = new AccountDto.BuyLimitUse();
        use.itemId = 1;
        use.name = "Gold leaf";
        use.limit = 11_000;
        use.bought = 60;
        use.remaining = 10_940;
        use.resetsAt = Instant.now().getEpochSecond() + 3 * 3600 + 50 * 60 + 30;
        account.buyLimits = List.of(use);
        return account;
    }

    @Test
    @SuppressWarnings("unchecked")
    void looksUpFlipsWithTheToolThenAnswers() throws Exception {
        when(accounts.find(eq(42L), anyLong())).thenReturn(Optional.of(account()));
        when(flips.find(any(), anyInt(), anyInt())).thenReturn(goldLeafPage());
        when(llm.complete(anyList(), anyList(), any()))
                .thenReturn(new LlmClient.Reply("", List.of(
                        new LlmClient.ToolCall("call-1", "find_flips", "{\"search\":\"gold\",\"sort\":\"roi\"}")), "tool_calls"))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<String>>getArgument(2).accept("Flip gold leaf.");
                    return new LlmClient.Reply("Flip gold leaf.", List.of(), "stop");
                });

        chat.chat(ask(42L, "Is gold leaf worth flipping?"), events::add);

        // A named item is looked up for the account without the activity filters.
        ArgumentCaptor<FlipQuery> query = ArgumentCaptor.forClass(FlipQuery.class);
        verify(flips).find(query.capture(), eq(0), eq(FlipService.MAX_PAGE_SIZE));
        assertThat(query.getValue().search()).isEqualTo("gold");
        assertThat(query.getValue().sort()).isEqualTo(FlipQuery.Sort.ROI);
        assertThat(query.getValue().account()).isEqualTo(42L);
        assertThat(query.getValue().maxTradeAgeMinutes()).isZero();
        assertThat(query.getValue().minVolume5m()).isZero();

        assertThat(events).extracting(event -> event.get("type")).containsExactly("tool", "flips", "text", "done");
        assertThat(events.get(0).get("search")).isEqualTo("gold");
        assertThat(events.get(2).get("text")).isEqualTo("Flip gold leaf.");

        // The second request carries the tool call and its result.
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(messages.capture(), anyList(), any());
        List<Map<String, Object>> second = messages.getAllValues().get(1);
        Map<String, Object> result = second.getLast();
        assertThat(result.get("role")).isEqualTo("tool");
        assertThat(result.get("tool_call_id")).isEqualTo("call-1");
        assertThat((String) result.get("content")).contains(
                "\"rank\":1,\"item\":\"Gold leaf\",\"line\":\"**Gold leaf**: buy ", "\"budget\":\"9,001,000 gp\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsOnlyTheItemASearchNamesExactly() throws Exception {
        ItemDto pie = FlipCalculatorTests.item();
        pie.name = "Dragonfruit pie";
        ItemDto fruit = FlipCalculatorTests.item();
        fruit.name = "Dragonfruit";
        FlipPageResponse page = new FlipPageResponse();
        page.items = List.of(
                FlipCalculator.calculate(pie, PriceBasis.LATEST, null),
                FlipCalculator.calculate(fruit, PriceBasis.LATEST, null));
        page.total = 2;
        when(flips.find(any(), anyInt(), anyInt())).thenReturn(page);
        when(llm.complete(anyList(), anyList(), any()))
                .thenReturn(new LlmClient.Reply("", List.of(
                        new LlmClient.ToolCall("call-1", "find_flips", "{\"search\":\"dragonfruit\"}")), "tool_calls"))
                .thenReturn(new LlmClient.Reply("", List.of(), "stop"));

        chat.chat(ask(null, "Is dragonfruit worth flipping?"), events::add);

        assertThat((List<FlipDto>) events.get(1).get("items")).extracting(flip -> flip.item.name)
                .containsExactly("Dragonfruit");
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(messages.capture(), anyList(), any());
        assertThat((String) messages.getAllValues().get(1).getLast().get("content"))
                .contains("\"item\":\"Dragonfruit\"")
                .doesNotContain("Dragonfruit pie");
    }

    @Test
    void writesEachFlipAsALineForTheModelToCopy() {
        FlipDto flip = new FlipDto();
        flip.item = FlipCalculatorTests.item();
        flip.item.name = "Old school bond";
        flip.buyPrice = 12_152_033L;
        flip.sellPrice = 12_250_000L;
        flip.margin = 97_967L;
        flip.estimatedProfit = 9_796_700L;
        flip.limitedBy = FlipDto.LimitedBy.BUY_LIMIT;

        flip.warning = "margin 5.6x usual, may not last";
        assertThat(ChatService.line(flip)).isEqualTo(
                "**Old school bond**: buy 12,152,033 gp, sell 12,250,000 gp, 97,967 gp each, about 9.8M profit, "
                        + "capped by the buy limit (margin 5.6x usual, may not last)");

        flip.warning = null;
        flip.estimatedProfit = 884_000L;
        flip.limitedBy = FlipDto.LimitedBy.VOLUME;
        assertThat(ChatService.line(flip)).endsWith("about 884K profit, capped by trading volume");

        flip.estimatedProfit = 0L;
        assertThat(ChatService.line(flip)).endsWith("but one side has not traded in the latest five minutes");

        flip.estimatedProfit = null;
        assertThat(ChatService.line(flip)).endsWith("97,967 gp each, buy limit unknown");
    }

    @Test
    void describesTheAccountInTheSystemPrompt() {
        String prompt = ChatService.systemPrompt(account(), Instant.now().getEpochSecond(), false);

        assertThat(prompt)
                .contains("Name: Flipper", "Member, with 30 days left", "9,001,000 gp")
                .contains("Gold leaf: 60 bought of 11,000, resets in 3h 50m")
                .contains("2% of the sale price");
    }

    @Test
    void explainsTheMissingPluginWithoutAnAccount() {
        String prompt = ChatService.systemPrompt(null, Instant.now().getEpochSecond(), false);

        assertThat(prompt).contains("has not connected the Flip Finder RuneLite plugin");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopsOfferingToolsAfterThreeRounds() throws Exception {
        when(flips.find(any(), anyInt(), anyInt())).thenReturn(goldLeafPage());
        LlmClient.Reply lookup = new LlmClient.Reply("", List.of(
                new LlmClient.ToolCall("call", "find_flips", "{}")), "tool_calls");
        when(llm.complete(anyList(), anyList(), any()))
                .thenReturn(lookup, lookup, lookup, new LlmClient.Reply("Done.", List.of(), "stop"));

        chat.chat(ask(null, "What should I flip?"), events::add);

        ArgumentCaptor<List<Map<String, Object>>> tools = ArgumentCaptor.forClass(List.class);
        verify(llm, times(4)).complete(anyList(), tools.capture(), any());
        assertThat(tools.getAllValues()).extracting(List::size).containsExactly(1, 1, 1, 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void looksUpAnItemsHistoryWhenHistoryIsKept() throws Exception {
        PriceHistoryService history = mock(PriceHistoryService.class);
        ChatService chat = chat(Optional.of(history));
        ItemDto leaf = FlipCalculatorTests.item();
        ItemHistoryDto summary = new ItemHistoryDto();
        summary.name = "Gold leaf";
        summary.volume = new ItemHistoryDto.Volume();
        summary.volume.verdict = "unusually high";
        summary.margin = new ItemHistoryDto.Margin();
        summary.notes = List.of("1,000 traded in the latest five minutes, against a typical 100.");
        when(history.find("gold leaf")).thenReturn(Optional.of(new PriceHistoryService.Match(leaf, List.of("Gold leaf boots"))));
        when(history.summarize(eq(leaf), anyLong())).thenReturn(summary);
        when(llm.complete(anyList(), anyList(), any()))
                .thenReturn(new LlmClient.Reply("", List.of(
                        new LlmClient.ToolCall("call-1", "item_history", "{\"item\":\"gold leaf\"}")), "tool_calls"))
                .thenReturn(new LlmClient.Reply("It is unusually busy.", List.of(), "stop"));

        chat.chat(ask(null, "Is gold leaf trading normally?"), events::add);

        assertThat(events).extracting(event -> event.get("type")).containsExactly("tool", "history", "done");
        assertThat(events.get(0)).containsEntry("name", "item_history").containsEntry("search", "gold leaf");
        assertThat(events.get(1)).containsEntry("history", summary);

        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Map<String, Object>>> tools = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(messages.capture(), tools.capture(), any());
        assertThat(tools.getAllValues().getFirst()).containsExactly(ChatService.FIND_FLIPS, ChatService.ITEM_HISTORY);
        assertThat((String) messages.getAllValues().get(1).getFirst().get("content")).contains("Trading history:");
        assertThat((String) messages.getAllValues().get(1).getLast().get("content"))
                .contains("\"item\":\"Gold leaf\"", "\"otherItemsMatchingTheName\":[\"Gold leaf boots\"]",
                        "\"volume\":\"unusually high\"", "against a typical 100");
    }

    @Test
    void reportsAModelThatCannotBeReached() throws Exception {
        when(llm.complete(anyList(), anyList(), any())).thenThrow(new IOException("Connection refused"));

        chat.chat(ask(null, "What should I flip?"), events::add);

        assertThat(events).extracting(event -> event.get("type")).containsExactly("error", "done");
        assertThat(events.getFirst()).containsEntry("message", ChatService.UNREACHABLE_MESSAGE);
        assertThat(time.sleeps).isEmpty();
    }

    @Test
    void asksAReadyModelWithoutWaiting() throws Exception {
        when(llm.complete(anyList(), anyList(), any())).thenAnswer(streams("Flip gold leaf."));

        chat.chat(ask(null, "What should I flip?"), events::add);

        assertThat(events).extracting(event -> event.get("type")).containsExactly("text", "done");
        assertThat(time.sleeps).isEmpty();
        verify(llm, times(1)).health();
    }

    @Test
    void waitsForAModelThatIsStillLoading() throws Exception {
        // Checked before asking, then every two seconds: loading twice more, then ready.
        when(llm.health()).thenReturn(LlmClient.Health.STARTING, LlmClient.Health.STARTING,
                LlmClient.Health.STARTING, LlmClient.Health.READY);
        when(llm.complete(anyList(), anyList(), any())).thenAnswer(streams("Flip gold leaf."));

        chat.chat(ask(null, "What should I flip?"), events::add);

        assertThat(events).extracting(event -> event.get("type")).containsExactly("status", "text", "done");
        assertThat(events.getFirst()).containsEntry("message", ChatService.STARTING_MESSAGE);
        assertThat(time.sleeps).containsExactly(STARTUP_POLL, STARTUP_POLL, STARTUP_POLL);
        verify(llm, times(4)).health();
        verify(llm, times(1)).complete(anyList(), anyList(), any());
    }

    @Test
    void givesUpOnAModelThatNeverFinishesStarting() throws Exception {
        when(llm.health()).thenReturn(LlmClient.Health.STARTING);

        chat.chat(ask(null, "What should I flip?"), events::add);

        assertThat(events).extracting(event -> event.get("type")).containsExactly("status", "error", "done");
        assertThat(events.get(1)).containsEntry("message", ChatService.STILL_STARTING_MESSAGE);
        // It waits the whole 90 seconds, two at a time, and never sends the question.
        assertThat(time.sleeps).hasSize(45).allMatch(STARTUP_POLL::equals);
        verify(llm, never()).complete(anyList(), anyList(), any());
    }

    @Test
    void waitsWhenTheModelRefusesTheQuestionWhileStarting() throws Exception {
        // The health check missed the start, as when the model stopped just after it,
        // so the refused connection is what shows it is starting.
        when(llm.health()).thenReturn(LlmClient.Health.READY, LlmClient.Health.STARTING, LlmClient.Health.READY);
        when(llm.complete(anyList(), anyList(), any()))
                .thenThrow(new LlmClient.StartingException("Connection refused", null))
                .thenAnswer(streams("Flip gold leaf."));

        chat.chat(ask(null, "What should I flip?"), events::add);

        assertThat(events).extracting(event -> event.get("type")).containsExactly("status", "text", "done");
        assertThat(events.get(1)).containsEntry("text", "Flip gold leaf.");
        assertThat(time.sleeps).containsExactly(STARTUP_POLL, STARTUP_POLL);
        verify(llm, times(2)).complete(anyList(), anyList(), any());
    }

    @Test
    void waitsForTheModelOnlyOncePerAnswer() throws Exception {
        when(llm.health()).thenReturn(LlmClient.Health.STARTING, LlmClient.Health.READY);
        when(llm.complete(anyList(), anyList(), any()))
                .thenThrow(new LlmClient.StartingException("The language model returned HTTP 503", null));

        chat.chat(ask(null, "What should I flip?"), events::add);

        assertThat(events).extracting(event -> event.get("type")).containsExactly("status", "error", "done");
        assertThat(events.get(1)).containsEntry("message", ChatService.STILL_STARTING_MESSAGE);
        verify(llm, times(1)).complete(anyList(), anyList(), any());
    }

    @Test
    void asksAnywayWhenTheHealthCheckStopsSayingStarting() throws Exception {
        // A server without a health check, or one that answers it oddly, gets the question
        // and fails or answers as it would have without the wait.
        when(llm.health()).thenReturn(LlmClient.Health.STARTING, LlmClient.Health.UNKNOWN);
        when(llm.complete(anyList(), anyList(), any())).thenThrow(new IOException("HTTP 500"));

        chat.chat(ask(null, "What should I flip?"), events::add);

        assertThat(events).extracting(event -> event.get("type")).containsExactly("status", "error", "done");
        assertThat(events.get(1)).containsEntry("message", ChatService.UNREACHABLE_MESSAGE);
        assertThat(time.sleeps).containsExactly(STARTUP_POLL);
    }

    @Test
    void stopsWaitingWhenInterrupted() throws Exception {
        when(llm.health()).thenReturn(LlmClient.Health.STARTING);
        ChatService chat = new ChatService(llm, flips, accounts, Optional.empty(), new ObjectMapper(),
                STARTUP_WAIT, STARTUP_POLL, time, duration -> {
                    throw new InterruptedException();
                });

        try {
            chat.chat(ask(null, "What should I flip?"), events::add);

            assertThat(events).extracting(event -> event.get("type")).containsExactly("status", "error", "done");
            assertThat(events.get(1)).containsEntry("message", "The answer was interrupted.");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
