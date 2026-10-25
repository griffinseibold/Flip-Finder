package com.flipfinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LlmClientTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    // What the stand-in's /health answers: 200 when llama.cpp is ready, 503 while it loads the model.
    private final AtomicInteger healthStatus = new AtomicInteger(200);
    private HttpServer server;

    /**
     * Starts a server that answers /v1/chat/completions with the given status
     * and event-stream lines, and /health with {@link #healthStatus}.
     */
    private LlmClient serve(int status, String... events) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(healthStatus.get(), -1);
            exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String event : events) {
                    out.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        });
        server.start();
        return new LlmClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "", "");
    }

    /** A client for a port nothing listens on, like the homelab's model before it starts. */
    private LlmClient stopped() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return new LlmClient(mapper, "http://127.0.0.1:" + port + "/v1", "", "");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsTextAsItArrivesAndReturnsTheWholeReply() throws Exception {
        LlmClient client = serve(200,
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Buy \"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"gold leaf.\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]");
        List<String> pieces = new ArrayList<>();

        LlmClient.Reply reply = client.complete(List.of(LlmClient.message("user", "What should I flip?")),
                List.of(), pieces::add);

        assertThat(pieces).containsExactly("Buy ", "gold leaf.");
        assertThat(reply.content()).isEqualTo("Buy gold leaf.");
        assertThat(reply.toolCalls()).isEmpty();
        assertThat(reply.finishReason()).isEqualTo("stop");
    }

    @Test
    void assemblesToolCallsFromTheirPieces() throws Exception {
        LlmClient client = serve(200,
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"find_flips\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"sort\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"roi\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]");

        LlmClient.Reply reply = client.complete(List.of(LlmClient.message("user", "Best ROI?")),
                List.of(ChatService.FIND_FLIPS), text -> { });

        assertThat(reply.toolCalls()).containsExactly(new LlmClient.ToolCall("call-1", "find_flips", "{\"sort\":\"roi\"}"));
        assertThat(reply.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void streamsWithThinkingOffAndOffersTheTools() throws Exception {
        LlmClient client = serve(200, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", "[DONE]");

        client.complete(List.of(LlmClient.message("user", "Hi")), List.of(ChatService.FIND_FLIPS), text -> { });

        JsonNode body = mapper.readTree(requestBody.get());
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("find_flips");
        assertThat(body.has("model")).isFalse();
    }

    @Test
    void failsWhenTheServerReturnsAnError() throws Exception {
        LlmClient client = serve(500);

        assertThatThrownBy(() -> client.complete(List.of(LlmClient.message("user", "Hi")), List.of(), text -> { }))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(LlmClient.StartingException.class)
                .hasMessageContaining("500");
    }

    @Test
    void treatsA503AsAModelStillLoading() throws Exception {
        LlmClient client = serve(503);

        assertThatThrownBy(() -> client.complete(List.of(LlmClient.message("user", "Hi")), List.of(), text -> { }))
                .isInstanceOf(LlmClient.StartingException.class)
                .hasMessageContaining("503");
    }

    @Test
    void treatsARefusedConnectionAsAModelNotStartedYet() throws Exception {
        LlmClient client = stopped();

        assertThatThrownBy(() -> client.complete(List.of(LlmClient.message("user", "Hi")), List.of(), text -> { }))
                .isInstanceOf(LlmClient.StartingException.class);
        assertThat(client.health()).isEqualTo(LlmClient.Health.STARTING);
    }

    @Test
    void reportsHealthFromTheServersHealthCheck() throws Exception {
        LlmClient client = serve(200);

        assertThat(client.health()).isEqualTo(LlmClient.Health.READY);
        healthStatus.set(503);
        assertThat(client.health()).isEqualTo(LlmClient.Health.STARTING);
        // Anything else says nothing about starting, so the question is sent as before.
        healthStatus.set(404);
        assertThat(client.health()).isEqualTo(LlmClient.Health.UNKNOWN);
    }

    @Test
    void doesNotMistakeAWrongHostForAStartingModel() throws Exception {
        // The homelab keeps the model's Service while it is stopped, so a name that does
        // not resolve is a wrong URL: it should fail at once rather than wait 90 seconds.
        LlmClient client = new LlmClient(mapper, "http://no-such-host.invalid/v1", "", "");

        assertThat(client.health()).isEqualTo(LlmClient.Health.UNKNOWN);
        assertThatThrownBy(() -> client.complete(List.of(LlmClient.message("user", "Hi")), List.of(), text -> { }))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(LlmClient.StartingException.class);
    }

    @Test
    void checksHealthAtTheServerRootWithoutTheApiPath() {
        assertThat(LlmClient.healthUri("http://llama-server.llm.svc.cluster.local/v1", ""))
                .isEqualTo(URI.create("http://llama-server.llm.svc.cluster.local/health"));
        assertThat(LlmClient.healthUri("http://llm.localhost:8080/v1/", null))
                .isEqualTo(URI.create("http://llm.localhost:8080/health"));
        assertThat(LlmClient.healthUri("http://127.0.0.1:8080", " "))
                .isEqualTo(URI.create("http://127.0.0.1:8080/health"));
        // Only a trailing /v1 is the API's; the rest of the path is where the server lives.
        assertThat(LlmClient.healthUri("https://example.com/llama/v1", ""))
                .isEqualTo(URI.create("https://example.com/llama/health"));
        assertThat(LlmClient.healthUri("http://example.com/v1beta", ""))
                .isEqualTo(URI.create("http://example.com/v1beta/health"));
    }

    @Test
    void usesAHealthUrlSetExplicitly() {
        assertThat(LlmClient.healthUri("http://example.com/v1", "http://example.com/ready"))
                .isEqualTo(URI.create("http://example.com/ready"));
        assertThat(new LlmClient(mapper, "http://example.com/v1", "http://example.com/ready", "").healthUri())
                .isEqualTo(URI.create("http://example.com/ready"));
    }

    @Test
    void refusesAUrlItCouldNeverReach() {
        assertThatThrownBy(() -> new LlmClient(mapper, "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flipfinder.llm.base-url");
        assertThatThrownBy(() -> new LlmClient(mapper, "llama-server.llm.svc.cluster.local/v1", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flipfinder.llm.base-url");
        assertThatThrownBy(() -> new LlmClient(mapper, "http://example.com/v1", "/health", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flipfinder.llm.health-url");
    }
}
