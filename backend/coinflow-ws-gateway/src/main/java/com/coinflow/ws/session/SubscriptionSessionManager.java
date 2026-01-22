package com.coinflow.ws.session;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionSessionManager {

    // Topic(Symbol) -> Set<SessionId>
    private final ConcurrentHashMap<String, Set<String>> topicSubscribers = new ConcurrentHashMap<>();

    // SessionId -> Set<Topic(Symbol)>
    private final ConcurrentHashMap<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();

    public void subscribe(String sessionId, String symbol) {
        // Add to topic -> sessions
        topicSubscribers.computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet()).add(sessionId);

        // Add to session -> topics
        sessionSubscriptions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(symbol);
    }

    public void unsubscribe(String sessionId, String symbol) {
        // Remove from topic -> sessions
        Set<String> sessions = topicSubscribers.get(symbol);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                topicSubscribers.remove(symbol);
            }
        }

        // Remove from session -> topics
        Set<String> topics = sessionSubscriptions.get(sessionId);
        if (topics != null) {
            topics.remove(symbol);
            if (topics.isEmpty()) {
                sessionSubscriptions.remove(sessionId);
            }
        }
    }

    public void removeSession(String sessionId) {
        Set<String> topics = sessionSubscriptions.remove(sessionId);
        if (topics != null) {
            for (String symbol : topics) {
                Set<String> sessions = topicSubscribers.get(symbol);
                if (sessions != null) {
                    sessions.remove(sessionId);
                    if (sessions.isEmpty()) {
                        topicSubscribers.remove(symbol);
                    }
                }
            }
        }
    }

    public Set<String> getSubscribers(String symbol) {
        return topicSubscribers.getOrDefault(symbol, Collections.emptySet());
    }
}
