package org.example.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class QueryState {

    private final List<Message> messages;
    private int turnCount = 1;
    private int continuationCount = 0;
    private boolean hasAttemptedCompact = false;
    private boolean stopHookActive = false;
    private Integer maxOutputTokensOverride;
    private TransitionReason lastTransition;

    private QueryState(List<Message> messages, Integer maxOutputTokensOverride) {
        this.messages = new ArrayList<>(messages);
        this.maxOutputTokensOverride = maxOutputTokensOverride;
    }

    public static QueryState from(QueryParams params) {
        return new QueryState(params.messages(), params.maxOutputTokensOverride());
    }

    // --- Read accessors ---

    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }

    public int turnCount() {
        return turnCount;
    }

    public int continuationCount() {
        return continuationCount;
    }

    public boolean hasAttemptedCompact() {
        return hasAttemptedCompact;
    }

    public boolean stopHookActive() {
        return stopHookActive;
    }

    public Optional<Integer> maxOutputTokensOverride() {
        return Optional.ofNullable(maxOutputTokensOverride);
    }

    public Optional<TransitionReason> lastTransition() {
        return Optional.ofNullable(lastTransition);
    }

    // --- Mutation methods ---

    public void appendMessage(Message m) {
        messages.add(m);
    }

    public void incrementTurn() {
        turnCount++;
    }

    public void incrementContinuation() {
        continuationCount++;
    }

    public void markCompactAttempted() {
        hasAttemptedCompact = true;
    }

    public void replaceMessages(List<Message> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
    }

    public void setStopHookActive(boolean active) {
        stopHookActive = active;
    }

    public void setMaxOutputTokensOverride(Integer v) {
        maxOutputTokensOverride = v;
    }

    public void setLastTransition(TransitionReason t) {
        lastTransition = t;
    }
}
