package com.flipfinder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipfinder.config.ChatEnabledCondition;
import com.flipfinder.dto.ChatRequest;
import com.flipfinder.service.ChatService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Chat with the homelab's language model. Setting flipfinder.llm.enabled and
 * flipfinder.llm.base-url enables it; see {@link ChatEnabledCondition}.
 */
@RestController
@Conditional(ChatEnabledCondition.class)
@Tag(name = "Chat", description = "Ask the homelab's language model about flips")
public class ChatController {
	static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
	private static final int MAX_MESSAGE_LENGTH = 4_000;

	private final ChatService chat;
	private final ObjectMapper mapper;

	public ChatController(ChatService chat, ObjectMapper mapper) {
		this.chat = chat;
		this.mapper = mapper;
	}

	@PostMapping(value = "/api/chat", produces = "application/x-ndjson")
	@Operation(summary = "Ask about flips", description = """
			Answers the conversation's last message as newline-delimited JSON events: "status" when the \
			answer waits for the model to start, "tool" when the model starts looking up flips, "flips" with \
			what it found, "text" pieces of the answer as they are written, then "done". An "error" event \
			means the model could not answer.""")
	public ResponseEntity<StreamingResponseBody> chat(@RequestBody ChatRequest request) {
		if (request.messages == null || request.messages.isEmpty()
				|| !"user".equals(request.messages.getLast().role)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The last message must be the player's");
		}
		if (request.messages.stream().anyMatch(m -> m.content != null && m.content.length() > MAX_MESSAGE_LENGTH)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Messages are limited to 4,000 characters");
		}
		StreamingResponseBody body = out -> chat.chat(request, event -> write(out, event));
		return ResponseEntity.ok().contentType(NDJSON).body(body);
	}

	private void write(OutputStream out, Object event) {
		try {
			out.write(mapper.writeValueAsBytes(event));
			out.write('\n');
			out.flush();
		} catch (IOException e) {
			// The browser went away; stop answering.
			throw new UncheckedIOException(e);
		}
	}
}
