package com.example.demo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final SignalingHandler signalingHandler;

    public WebSocketConfig(SignalingHandler signalingHandler) {
        this.signalingHandler = signalingHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(signalingHandler, "/ws")
                .setAllowedOrigins("*");
    }
}

@Component
class SignalingHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    
    private final AtomicBoolean callerAssigned = new AtomicBoolean(false);
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

   @Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

    String payload = message.getPayload();

   
    if (payload.contains("\"type\":\"offer\"")) {
        storedOffer = payload;
        System.out.println("Offer stored");
    }

    
    if (payload.contains("\"type\":\"answer\"")) {
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

    callerAssigned.set(false);
    storedOffer = null;

    System.out.println("Disconnected: " + session.getId());
    System.out.println("Total sessions: " + sessions.size());
}
}