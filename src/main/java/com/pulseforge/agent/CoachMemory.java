package com.pulseforge.agent;

import com.pulseforge.llm.LlmRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Short-term conversational memory (UC09 postcondition: "the conversation turn is added to
 * chat memory history"). Keeps the last {@code capacity} question/answer pairs so follow-up
 * questions like "what about tomorrow?" have context.
 */
public class CoachMemory {

    public record Turn(String question, String answer) {
    }

    private final int capacity;
    private final Deque<Turn> turns = new ArrayDeque<>();

    public CoachMemory(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(String question, String answer) {
        turns.addLast(new Turn(question, answer));
        while (turns.size() > capacity) {
            turns.removeFirst();
        }
    }

    public synchronized List<LlmRequest.Message> asMessages() {
        List<LlmRequest.Message> out = new ArrayList<>();
        for (Turn t : turns) {
            out.add(LlmRequest.Message.user(t.question()));
            out.add(LlmRequest.Message.assistant(t.answer()));
        }
        return out;
    }

    public synchronized List<Turn> turns() {
        return new ArrayList<>(turns);
    }

    public synchronized void clear() {
        turns.clear();
    }
}
