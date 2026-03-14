package com.example.demo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Spring Boot entry point for the WebRTC signaling server.
 */
@SpringBootApplication
public class DemoApplication {
    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

/**
 * WebSocket configuration for registering signaling endpoints.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final SignalingHandler signalingHandler;

    public WebSocketConfig(SignalingHandler signalingHandler) {
        this.signalingHandler = signalingHandler;
    }

    /**
     * Registers the signaling handler under {@code /ws}.
     *
     * @param registry WebSocket handler registry
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(signalingHandler, "/ws")
                .setAllowedOrigins("*");
    }
}

/**
 * WebSocket-based signaling handler for 1:1 WebRTC peers.
 *
 * <p>The first client is assigned the {@code caller} role and the second client
 * is assigned the {@code receiver} role. The latest offer is cached so that a
 * receiver that joins after the caller can immediately receive it. Most incoming
 * signaling messages are relayed to the opposite peer.</p>
 */
@Component
class SignalingHandler extends TextWebSocketHandler {

    private static final Pattern TYPE_PATTERN = Pattern.compile("\\\"type\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    /** Active WebSocket sessions (max 2 peers). */
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    /** Indicates whether a caller role is already assigned. */
    private final AtomicBoolean callerAssigned = new AtomicBoolean(false);

    /** Last received offer payload waiting for a receiver. */
    private volatile String storedOffer = null;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {

        if (sessions.size() >= 2) {
            session.close();
            return;
        }

        sessions.add(session);

        boolean isCaller = callerAssigned.compareAndSet(false, true);

        if (isCaller) {
            session.sendMessage(new TextMessage("{\"type\":\"caller\"}"));
            System.out.println("Caller assigned");
        } else {
            session.sendMessage(new TextMessage("{\"type\":\"receiver\"}"));
            System.out.println("Receiver assigned");

            if (storedOffer != null) {
                session.sendMessage(new TextMessage(storedOffer));
                System.out.println("Stored offer delivered to receiver");
            }
        }
    }

    /**
     * Handles incoming signaling messages and relays them to the opposite peer.
     *
     * <p>Special handling:</p>
     * <ul>
     *   <li>{@code join}: ignored to avoid unnecessary relay noise</li>
     *   <li>{@code offer}: stored so late receiver can get it immediately</li>
     *   <li>{@code answer}: clears stored offer after handshake is completed</li>
     * </ul>
     *
     * @param session sending session
     * @param message incoming text message
     * @throws Exception if relay fails
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        String payload = message.getPayload();
        String type = extractType(payload);

        if (type == null || type.isBlank()) {
            System.out.println("Ignoring invalid signaling payload");
            return;
        }

        if ("join".equals(type)) {
            return;
        }

        if (!"offer".equals(type) && !"answer".equals(type) && !"candidate".equals(type)) {
            System.out.println("Ignoring unsupported message type: " + type);
            return;
        }

        if ("offer".equals(type)) {
            storedOffer = payload;
            System.out.println("Offer stored");
        }

        if ("answer".equals(type)) {
            storedOffer = null;
            System.out.println("Offer cleared after answer");
        }

        for (WebSocketSession s : sessions) {
            if (!s.getId().equals(session.getId()) && s.isOpen()) {
                s.sendMessage(message);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {

        sessions.remove(session);

        if (sessions.isEmpty()) {
            callerAssigned.set(false);
            storedOffer = null;
        }

        System.out.println("Disconnected: " + session.getId());
        System.out.println("Total sessions: " + sessions.size());
    }

    private String extractType(String payload) {
        Matcher matcher = TYPE_PATTERN.matcher(payload);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}