package com.flipfinder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flipfinder.controller.ChatController;
import com.flipfinder.service.ChatService;
import com.flipfinder.service.LlmClient;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The homelab profile's settings with the chat switched off, as the chart's
 * llm.enabled=false does: the URL keeps its default, but there is no chat.
 */
@SpringBootTest(properties = { "flipfinder.scheduling.enabled=false",
		"flipfinder.llm.base-url=http://llama-server.llm.svc.cluster.local/v1", "flipfinder.llm.enabled=false" })
@AutoConfigureMockMvc
class ChatDisabledTests {
	@Autowired
	private ApplicationContext context;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void hasNoChatBeans() {
		assertThat(context.getBeanNamesForType(LlmClient.class)).isEmpty();
		assertThat(context.getBeanNamesForType(ChatService.class)).isEmpty();
		assertThat(context.getBeanNamesForType(ChatController.class)).isEmpty();
	}

	@Test
	void reportsTheChatAsOffAndHasNoEndpoint() throws Exception {
		mockMvc.perform(get("/api/features"))
				.andExpect(jsonPath("$.chat").value(false));
		mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
				.content("{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"))
				.andExpect(status().is4xxClientError());
	}
}
