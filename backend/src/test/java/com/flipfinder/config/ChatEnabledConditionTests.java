package com.flipfinder.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipfinder.controller.ChatController;
import com.flipfinder.controller.FeaturesController;
import com.flipfinder.service.AccountService;
import com.flipfinder.service.ChatService;
import com.flipfinder.service.FlipService;
import com.flipfinder.service.LlmClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The chat exists only when flipfinder.llm.enabled is true and
 * flipfinder.llm.base-url is set. These start just the chat's beans, so every
 * combination of the two settings is quick to check.
 */
class ChatEnabledConditionTests {
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			// Binds durations such as 90s, as a full Spring Boot application does.
			.withInitializer(context -> context.getBeanFactory()
					.setConversionService(ApplicationConversionService.getSharedInstance()))
			.withBean(ObjectMapper.class)
			.withBean(FlipService.class, () -> mock(FlipService.class))
			.withBean(AccountService.class, () -> mock(AccountService.class))
			.withUserConfiguration(LlmClient.class, ChatService.class, ChatController.class, FeaturesController.class);

	@Test
	void isOnWithTheSwitchAndAUrl() {
		runner.withPropertyValues("flipfinder.llm.enabled=true", "flipfinder.llm.base-url=http://127.0.0.1:1/v1")
				.run(context -> {
					assertThat(context).hasSingleBean(LlmClient.class).hasSingleBean(ChatService.class)
							.hasSingleBean(ChatController.class);
					assertThat(context.getBean(FeaturesController.class).features()).containsEntry("chat", true);
				});
	}

	@Test
	void isOffWhenSwitchedOff() {
		runner.withPropertyValues("flipfinder.llm.enabled=false", "flipfinder.llm.base-url=http://127.0.0.1:1/v1")
				.run(context -> {
					assertThat(context).hasNotFailed().doesNotHaveBean(LlmClient.class)
							.doesNotHaveBean(ChatService.class).doesNotHaveBean(ChatController.class);
					assertThat(context.getBean(FeaturesController.class).features()).containsEntry("chat", false);
				});
	}

	@Test
	void isOffWithoutTheSwitch() {
		// Before the switch existed a URL alone turned the chat on; now it takes both.
		runner.withPropertyValues("flipfinder.llm.base-url=http://127.0.0.1:1/v1")
				.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatService.class));
	}

	@Test
	void isOffWithAnEmptyUrlRatherThanBuildingABrokenClient() {
		// An empty FLIPFINDER_LLM_URL would otherwise make a client for the relative URL /chat/completions.
		runner.withPropertyValues("flipfinder.llm.enabled=true", "flipfinder.llm.base-url=")
				.run(context -> {
					assertThat(context).hasNotFailed().doesNotHaveBean(LlmClient.class);
					assertThat(context.getBean(FeaturesController.class).features()).containsEntry("chat", false);
				});
		runner.withPropertyValues("flipfinder.llm.enabled=true", "flipfinder.llm.base-url=  ")
				.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LlmClient.class));
	}

	@Test
	void failsToStartOnAUrlItCouldNeverReach() {
		runner.withPropertyValues("flipfinder.llm.enabled=true", "flipfinder.llm.base-url=llama-server/v1")
				.run(context -> assertThat(context).hasFailed().getFailure()
						.rootCause().hasMessageContaining("flipfinder.llm.base-url"));
	}
}
