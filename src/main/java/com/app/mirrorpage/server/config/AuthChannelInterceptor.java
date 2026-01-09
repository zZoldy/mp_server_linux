package com.app.mirrorpage.server.config;

import com.app.mirrorpage.server.security.JwtService;
import com.app.mirrorpage.server.service.ActiveUserManager;
import com.app.mirrorpage.server.service.ServerLog;
import com.app.mirrorpage.server.service.UserService;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AuthChannelInterceptor implements ChannelInterceptor {

    private final ActiveUserManager activeUserManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final ServerLog serverLog;

    public AuthChannelInterceptor(@Lazy ActiveUserManager activeUserManager,
            JwtService jwtService,
            UserService userService,
            ServerLog serverLog) {
        this.activeUserManager = activeUserManager;
        this.jwtService = jwtService;
        this.userService = userService;
        this.serverLog = serverLog;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && accessor.getCommand() != null) {

            // --- EVENTO DE CONEXÃO (CONNECT) ---
            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                String username = null;
                // 2. FALLBACK: Header STOMP
// 1. Tenta extrair o token do Header nativo (Mais confiável no STOMP)
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);
                    if (jwtService.isValid(token)) {
                        username = jwtService.getUsername(token);
                        System.out.println("Username pelo token: " + username);

                        // [CRUCIAL] Cria a identidade para o Spring WebSocket
                        UserDetails userDetails = userService.loadUserByUsername(username);
                        UsernamePasswordAuthenticationToken auth
                                = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                        // Carimba a sessão: Sem isso o convertAndSendToUser não funciona!
                        accessor.setUser(auth);
                    }
                }
                
                // 2. Se falhou o token, tenta o principal do handshake
                if (username == null && accessor.getUser() != null) {
                    username = accessor.getUser().getName();
                    System.out.println("Username pelo Principal: " + username);
                }

                // 3. REGISTRO E CONTROLE DE INSTÂNCIA
                if (username != null) {
                    // Pega o ID da Instância enviado pelo cliente (AppSocket ou SheetSocket)
                    String instanceId = accessor.getFirstNativeHeader("X-Instance-Id");
                    if (instanceId == null) {
                        instanceId = "unknown"; // Para clientes legados ou testes manuais
                    }

                    // Chama o Manager para registrar e verificar conflito de instância
                    // O Manager vai invalidar sessões antigas se o instanceId for diferente
                    Set<String> kickedSessions = activeUserManager.registerConnection(
                            accessor.getSessionId(),
                            username,
                            instanceId,
                            jwtService.getAccessMinutes()
                    );

                    if (!kickedSessions.isEmpty()) {
                        serverLog.info("AUTH", "Sessões antigas invalidadas para " + username + ": " + kickedSessions);
                        // Aqui poderíamos forçar o fechamento via WebSocketRegistry, 
                        // mas invalidar no Manager já impede ações futuras (SEND).
                        for (String oldSessionId : kickedSessions) {
                            activeUserManager.forceDisconnect(oldSessionId); // Manda comando de logout para as antigas
                        }
                    }

                    if (activeUserManager.getSessionCount(username) == 1) {
                        serverLog.info("AuthInterceptor", "Conectado: " + username + " [Instância: " + instanceId + "]");
                    } else {
                        // Opcional: Log de debug para conexões secundárias
                        // serverLog.debug("AuthInterceptor", "Conexão secundária (Planilha): " + username);
                    }
                    return message;
                }

                serverLog.info("AuthInterceptor", "Bloqueando: Usuário não identificado.");
                return null;

            } else if (StompCommand.SEND.equals(accessor.getCommand())) {
                // Validação extra: O usuário ainda é dono desta sessão?
                // Se ele logou em outro PC, essa sessão foi removida do mapa no registerConnection.
                if (!activeUserManager.isSessionValid(accessor.getSessionId())) {
                    serverLog.warn("AuthInterceptor", "Bloqueando SEND de sessão invalidada (Login em outro local).");
                    return null; // Bloqueia silenciosamente ou lança erro
                }
            } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
                activeUserManager.removeSession(accessor.getSessionId());
            }
        }

        return message;
    }

}
