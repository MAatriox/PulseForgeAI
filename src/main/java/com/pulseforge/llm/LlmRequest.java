package com.pulseforge.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider-neutral LLM request: a system prompt, an ordered conversation, and options.
 * Messages may carry an inline image for multimodal (vision) calls.
 */
public final class LlmRequest {

    public enum Role { USER, ASSISTANT }

    /** One conversation turn; {@code imageBytes} is optional. */
    public record Message(Role role, String text, byte[] imageBytes, String imageMimeType) {
        public static Message user(String text) {
            return new Message(Role.USER, text, null, null);
        }

        public static Message assistant(String text) {
            return new Message(Role.ASSISTANT, text, null, null);
        }

        public static Message userWithImage(String text, byte[] image, String mime) {
            return new Message(Role.USER, text, image, mime);
        }

        public boolean hasImage() {
            return imageBytes != null && imageBytes.length > 0;
        }
    }

    private final String systemPrompt;
    private final List<Message> messages;
    private final boolean jsonMode;
    private final double temperature;

    private LlmRequest(Builder b) {
        this.systemPrompt = b.systemPrompt;
        this.messages = Collections.unmodifiableList(new ArrayList<>(b.messages));
        this.jsonMode = b.jsonMode;
        this.temperature = b.temperature;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public List<Message> messages() {
        return messages;
    }

    public boolean jsonMode() {
        return jsonMode;
    }

    public double temperature() {
        return temperature;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String systemPrompt = "";
        private final List<Message> messages = new ArrayList<>();
        private boolean jsonMode;
        private double temperature = 0.4;

        public Builder system(String s) {
            this.systemPrompt = s;
            return this;
        }

        public Builder user(String text) {
            messages.add(Message.user(text));
            return this;
        }

        public Builder assistant(String text) {
            messages.add(Message.assistant(text));
            return this;
        }

        public Builder userWithImage(String text, byte[] image, String mime) {
            messages.add(Message.userWithImage(text, image, mime));
            return this;
        }

        public Builder messages(List<Message> msgs) {
            messages.addAll(msgs);
            return this;
        }

        public Builder json(boolean json) {
            this.jsonMode = json;
            return this;
        }

        public Builder temperature(double t) {
            this.temperature = t;
            return this;
        }

        public LlmRequest build() {
            if (messages.isEmpty()) {
                throw new IllegalStateException("An LLM request needs at least one message");
            }
            return new LlmRequest(this);
        }
    }
}
