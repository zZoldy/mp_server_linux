/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.config;

import com.app.mirrorpage.server.security.JwtService;
import com.app.mirrorpage.server.service.ActiveUserManager;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private final ActiveUserManager activeUserManager;

    public WebSocketEventListener(ActiveUserManager activeUserManager) {
        this.activeUserManager = activeUserManager;
    }

    @EventListener
    public void handleSessionConnected(SessionConnectEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());

        String username = null;
        Principal userPrincipal = sha.getUser();
        if (userPrincipal != null) {
            username = userPrincipal.getName();
        }

        String instanceId = sha.getFirstNativeHeader("X-Instance-Id");

        // 3. Validação e Log (Crucial para Debug durante o programa ao vivo)
        if (instanceId == null) {
            System.out.println("[WebSocket] Conexão estabelecida SEM instance-id. Usuário: " + username);
        } else {
            System.out.println("[WebSocket] Conexão Ativa | User: " + username + " | Device ID: " + instanceId + " | Session ID: " + sha.getSessionId());
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
