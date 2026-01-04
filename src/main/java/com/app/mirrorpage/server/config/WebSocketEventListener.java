/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.config;

import com.app.mirrorpage.server.security.JwtService;
import com.app.mirrorpage.server.service.ActiveUserManager;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private final ActiveUserManager activeUserManager;
    private final JwtService jwtService;

    public WebSocketEventListener(ActiveUserManager activeUserManager, JwtService jwtService) {
        this.activeUserManager = activeUserManager;
        this.jwtService = jwtService;
    }

    @EventListener
    public void handleSessionConnected(SessionConnectEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());

        // Cliente Java DEVE enviar esse header ao conectar
        String username = sha.getFirstNativeHeader("login-user");
        String sessionId = sha.getSessionId();

        if (username != null) {
            int expirationMillis = jwtService.getAccessMinutes();
            activeUserManager.addSession(sessionId, username, expirationMillis);
        }
    }

    // --- ESSE É O EVENTO DE SAÍDA ---
    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = sha.getSessionId();

        // Avisa o Manager para remover a sessão e limpar locks
        if (sessionId != null) {
            activeUserManager.removeSession(sessionId);
        }
    }
}
