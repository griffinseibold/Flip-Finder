package com.flipfinder.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Turns the chat on when {@code flipfinder.llm.enabled} is true and
 * {@code flipfinder.llm.base-url} names a server. The language model client,
 * its readiness check, the chat service and its endpoint all use this one
 * condition, so they exist together or not at all.
 *
 * <p>Both are needed because a URL alone was ambiguous: Spring's
 * {@code @ConditionalOnProperty} counts an empty value as set, which built a
 * client for the relative URL "/chat/completions" when FLIPFINDER_LLM_URL was
 * set but empty. Requiring an explicit switch also lets the homelab turn the
 * chat off, and stop asking for its GPU, without unsetting the URL.
 */
public class ChatEnabledCondition extends SpringBootCondition {
    static final String ENABLED = "flipfinder.llm.enabled";
    static final String BASE_URL = "flipfinder.llm.base-url";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        PropertyResolver properties = context.getEnvironment();
        if (!properties.getProperty(ENABLED, Boolean.class, false)) {
            return ConditionOutcome.noMatch(ENABLED + " is not true");
        }
        if (properties.getProperty(BASE_URL, "").isBlank()) {
            return ConditionOutcome.noMatch(BASE_URL + " is empty");
        }
        return ConditionOutcome.match(ENABLED + " is true and " + BASE_URL + " is set");
    }
}
