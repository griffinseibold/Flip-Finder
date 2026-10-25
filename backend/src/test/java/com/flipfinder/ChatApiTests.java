package com.flipfinder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = { "flipfinder.scheduling.enabled=false", "flipfinder.runelite.enabled=true",
		"flipfinder.llm.enabled=true", "flipfinder.llm.startup-poll=10ms" })
@AutoConfigureMockMvc
class ChatApiTests {
	private static HttpServer llm;
	// How many more health checks answer 503, as llama.cpp does while it loads the model.
	private static final AtomicInteger loadingChecks = new AtomicInteger();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private final ObjectMapper mapper = new ObjectMapper();

	/** A stand-in for llama.cpp: looks up flips first, then answers once it has the result. */
	@BeforeAll
	static void startLanguageModel() throws IOException {
		llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		llm.createContext("/health", exchange -> {
			exchange.sendResponseHeaders(loadingChecks.getAndUpdate(n -> Math.max(n - 1, 0)) > 0 ? 503 : 200, -1);
			exchange.close();
		});
		llm.createContext("/v1/chat/completions", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			List<String> events = body.contains("tool_call_id")
					? List.of("{\"choices\":[{\"delta\":{\"content\":\"Gold leaf \"}}]}",
							"{\"choices\":[{\"delta\":{\"content\":\"looks good.\"},\"finish_reason\":\"stop\"}]}")
					: List.of("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
							+ "\"type\":\"function\",\"function\":{\"name\":\"find_flips\","
							+ "\"arguments\":\"{\\\"search\\\":\\\"gold\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				for (String event : events) {
					out.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
				}
				out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
			}
		});
		llm.start();
	}

	@AfterAll
	static void stopLanguageModel() {
		llm.stop(0);
	}

	@DynamicPropertySource
	static void languageModel(DynamicPropertyRegistry registry) {
		registry.add("flipfinder.llm.base-url", () -> "http://127.0.0.1:" + llm.getAddress().getPort() + "/v1");
	}

	@BeforeEach
	void items() {
		loadingChecks.set(0);
		jdbc.update("DELETE FROM items");
		long now = Instant.now().getEpochSecond();
		jdbc.update("""
				INSERT INTO items (
				  id, name, members, buy_limit, high_price, high_price_time, low_price, low_price_time,
				  high_price_volume_5m, low_price_volume_5m, updated_at
				) VALUES (8784, 'Gold leaf', 1, 11000, 141749, ?, 135479, ?, 30, 29, '2026-09-27T15:00:00Z')
				""", now, now);
	}

	@Test
	void streamsAnAnswerBuiltOnAFlipLookup() throws Exception {
		List<JsonNode> events = ask("Is gold leaf a good flip?");

		assertThat(events).extracting(event -> event.path("type").asText())
				.containsExactly("tool", "flips", "text", "text", "done");
		assertThat(events.get(1).path("items").get(0).path("item").path("name").asText()).isEqualTo("Gold leaf");
		assertThat(events.get(2).path("text").asText() + events.get(3).path("text").asText())
				.isEqualTo("Gold leaf looks good.");
	}

	@Test
	void waitsForTheModelToLoadThenAnswers() throws Exception {
		loadingChecks.set(3);

		List<JsonNode> events = ask("Is gold leaf a good flip?");

		assertThat(events).extracting(event -> event.path("type").asText())
				.containsExactly("status", "tool", "flips", "text", "text", "done");
		assertThat(events.getFirst().path("message").asText()).contains("is starting");
		assertThat(loadingChecks).hasValue(0);
	}

	@Test
	void rejectsAConversationThatDoesNotEndWithThePlayer() throws Exception {
		mockMvc.perform(post("/api/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"messages\":[{\"role\":\"assistant\",\"content\":\"Hi\"}]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void reportsTheChatAsAvailable() throws Exception {
		mockMvc.perform(get("/api/features"))
				.andExpect(jsonPath("$.chat").value(true))
				.andExpect(jsonPath("$.runelite").value(true));
	}

	private List<JsonNode> ask(String question) throws Exception {
		MvcResult started = mockMvc.perform(post("/api/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(
						Map.of("messages", List.of(Map.of("role", "user", "content", question))))))
				.andExpect(request().asyncStarted())
				.andReturn();

		String body = mockMvc.perform(asyncDispatch(started))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return body.lines().map(this::parse).toList();
	}

	private JsonNode parse(String line) {
		try {
			return mapper.readTree(line);
		} catch (IOException e) {
			throw new AssertionError("Not JSON: " + line, e);
		}
	}
}
