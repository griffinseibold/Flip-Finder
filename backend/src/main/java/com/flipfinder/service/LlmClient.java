package com.flipfinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.flipfinder.config.ChatEnabledCondition;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Streams chat completions from an OpenAI-compatible server, such as the
 * homelab's llama.cpp, and checks whether that server is ready to answer.
 */
@Component
@Conditional(ChatEnabledCondition.class)
public class LlmClient {
    private final ObjectMapper mapper;
    private final URI completionsUri;
    private final URI healthUri;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    // The readiness check is polled while the model starts, so it gives up on
    // a connection sooner than an answer does: a stopped server should cost a
    // poll a couple of seconds, not ten.
    private final HttpClient healthHttp = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public LlmClient(
            ObjectMapper mapper,
            @Value("${flipfinder.llm.base-url}") String baseUrl,
            @Value("${flipfinder.llm.health-url:}") String healthUrl,
            @Value("${flipfinder.llm.model:}") String model) {
        this.mapper = mapper;
        this.completionsUri = absolute(trimSlashes(baseUrl) + "/chat/completions", "flipfinder.llm.base-url");
        this.healthUri = healthUri(baseUrl, healthUrl);
        this.model = model;
    }

    /**
     * Where the server reports whether it is ready. llama.cpp serves its
     * OpenAI-compatible API under /v1 but its health check at the root, so
     * http://llama-server.llm.svc.cluster.local/v1 is checked at
     * http://llama-server.llm.svc.cluster.local/health. Set
     * flipfinder.llm.health-url for a server laid out differently.
     */
    static URI healthUri(String baseUrl, String healthUrl) {
        if (healthUrl != null && !healthUrl.isBlank()) {
            return absolute(healthUrl.trim(), "flipfinder.llm.health-url");
        }
        String root = trimSlashes(baseUrl).replaceFirst("/v1$", "");
        return absolute(root + "/health", "flipfinder.llm.base-url");
    }

    private static String trimSlashes(String url) {
        return url.trim().replaceFirst("/+$", "");
    }

    /**
     * Fails at startup on a URL the client could never reach, such as one
     * missing its http://, rather than on the first question.
     */
    private static URI absolute(String url, String property) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(property + " is not a valid URL: " + url, e);
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException(property + " must be an http:// or https:// URL, not " + url);
        }
        return uri;
    }

    URI healthUri() {
        return healthUri;
    }

    /** Whether the server can answer now, as far as its health check tells. */
    public enum Health {
        /** The health check answered 200. */
        READY,
        /**
         * The server is loading its model (HTTP 503) or is not running yet
         * (connection refused or timed out): the homelab starts it on demand,
         * so it should be ready shortly.
         */
        STARTING,
        /**
         * The health check said nothing useful, such as a 404 from a server
         * without one or a host that does not resolve. The question is sent
         * anyway, so such a server fails or answers exactly as it did before
         * there was a health check.
         */
        UNKNOWN
    }

    /** Asks the server whether it is ready, without waiting for it. */
    public Health health() throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            int status = healthHttp.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 200) {
                return Health.READY;
            }
            return status == 503 ? Health.STARTING : Health.UNKNOWN;
        } catch (IOException e) {
            return startingFailure(e) ? Health.STARTING : Health.UNKNOWN;
        }
    }

    /**
     * Whether a failed request means the server is not running yet. Inside the
     * cluster a stopped server's Service refuses connections or lets them time
     * out; outside it, through the Gateway, it answers 503 instead. A host
     * that does not resolve is left out: the homelab keeps the Service while
     * the model is stopped, so a missing name is a wrong URL, not a start, and
     * fails at once as it always has.
     */
    static boolean startingFailure(IOException e) {
        if (!(e instanceof ConnectException || e instanceof HttpConnectTimeoutException)) {
            return false;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnresolvedAddressException || cause instanceof UnknownHostException) {
                return false;
            }
        }
        return true;
    }

    /**
     * A request failed because the server is not running yet or is still
     * loading its model, as opposed to failing while it answered.
     */
    public static class StartingException extends IOException {
        private static final long serialVersionUID = 1L;

        public StartingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Sends the conversation and streams the reply, passing each piece of text
     * to {@code onText} as it arrives. Returns the whole reply, including any
     * tool calls the model made instead of answering.
     */
    public Reply complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onText)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!model.isBlank()) {
            body.put("model", model);
        }
        body.put("messages", messages);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }
        body.put("stream", true);
        body.put("temperature", 0.3);
        body.put("max_tokens", 800);
        // Qwen3 reasons at length before answering unless told not to.
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));

        HttpRequest request = HttpRequest.newBuilder(completionsUri)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            if (startingFailure(e)) {
                throw new StartingException("The language model is not running yet: " + e, e);
            }
            throw e;
        }

        try (Stream<String> lines = response.body()) {
            if (response.statusCode() == 503) {
                throw new StartingException("The language model returned HTTP 503 while loading", null);
            }
            if (response.statusCode() != 200) {
                throw new IOException("The language model returned HTTP " + response.statusCode());
            }
            StringBuilder content = new StringBuilder();
            Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
            String finishReason = null;
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    break;
                }
                JsonNode chunk = mapper.readTree(data);
                if (chunk.has("error")) {
                    throw new IOException("The language model failed: " + chunk.get("error"));
                }
                JsonNode choice = chunk.path("choices").path(0);
                JsonNode delta = choice.path("delta");
                String text = delta.path("content").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                    onText.accept(text);
                }
                for (JsonNode call : delta.path("tool_calls")) {
                    toolCalls.computeIfAbsent(call.path("index").asInt(), ToolCallBuilder::new).add(call);
                }
                if (choice.hasNonNull("finish_reason")) {
                    finishReason = choice.get("finish_reason").asText();
                }
            }
            return new Reply(content.toString(), toolCalls.values().stream().map(ToolCallBuilder::build).toList(),
                    finishReason);
        }
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    public record Reply(String content, List<ToolCall> toolCalls, String finishReason) {
    }

    /** Tool calls arrive in pieces: an id and name, then the arguments a few characters at a time. */
    private static final class ToolCallBuilder {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        ToolCallBuilder(int index) {
            this.index = index;
        }

        void add(JsonNode delta) {
            if (delta.hasNonNull("id")) {
                id = delta.get("id").asText();
            }
            JsonNode function = delta.path("function");
            if (function.hasNonNull("name")) {
                name = function.get("name").asText();
            }
            arguments.append(function.path("arguments").asText(""));
        }

        ToolCall build() {
            // Some servers leave out the id; the reply only needs to match it.
            return new ToolCall(id != null ? id : "call-" + index, name, arguments.isEmpty() ? "{}" : arguments.toString());
        }
    }

    /** Builds the message objects the API expects. */
    static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    static Map<String, Object> assistantToolCalls(String content, List<ToolCall> calls) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (ToolCall call : calls) {
            toolCalls.add(Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.arguments())));
        }
        Map<String, Object> message = message("assistant", content);
        message.put("tool_calls", toolCalls);
        return message;
    }

    static Map<String, Object> toolResult(String toolCallId, String content) {
        Map<String, Object> message = message("tool", content);
        message.put("tool_call_id", toolCallId);
        return message;
    }
}
