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

                String authHeader = accessor.getFirstNativeHeader("Authorization");
                String token = null;

                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    token = authHeader.substring(7);
                }

                if (token != null && jwtService.isValid(token)) {
                    String username = jwtService.getUsername(token);

                    if (username != null) {
                        UserDetails userDetails = userService.loadUserByUsername(username);

                        UsernamePasswordAuthenticationToken auth
                                = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        
                        // 1. AUTENTICA A SESSÃO (Isso é obrigatório para funcionar)
                        accessor.setUser(auth);

                        // 2. LÓGICA DE FILTRO: Só registra se NÃO estiver conectado
                        // Você precisa ter o método isUserConnected no seu ActiveUserManager
                        if (!activeUserManager.isUserConnected(username)) {
                            
                            // É a primeira conexão (Login Principal)
                            activeUserManager.addSession(accessor.getSessionId(), username);
                           
                        }
                        return message; 
                    }
                }

                serverLog.info("AuthIncerceptor", "Bloqueando: Token inválido ou ausente.");
                return null;
                
            } else if (StompCommand.SEND.equals(accessor.getCommand()) || StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                if (accessor.getUser() == null) {
                    serverLog.info("AuthIncerceptor", "Bloqueando ação: Usuário não autenticado");
                    return null;
                }
            } 
            // --- EVENTO DE DESCONEXÃO (DISCONNECT) ---
            else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
                // Ao desconectar, tentamos remover.
                // Se for uma conexão secundária que nós IGNORAMOS no CONNECT, 
                // o removeSession não vai achar o ID e não fará nada (o que é correto, 
                // pois não queremos deslogar o usuário principal só porque fechou a planilha).
                activeUserManager.removeSession(accessor.getSessionId());
            }
        }

        return message;
    }
}