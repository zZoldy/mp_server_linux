package com.app.mirrorpage.server.service;

import com.app.mirrorpage.server.tabel.CellLockService;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ActiveUserManager {

    // Injeção do serviço de Locks para poder limpar quando o usuário sair
    private final CellLockService lockService;

    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    private final ServerLog serverLog;

    public ActiveUserManager(CellLockService lockService, ServerLog serverLog) {
        this.lockService = lockService;
        this.serverLog = serverLog;
    }

    public void addSession(String sessionId, String username) {
        // Se chegou aqui, o Interceptor já garantiu que não é duplicado.
        activeSessions.put(sessionId, username);
        serverLog.info("AUTH", "Usuário logado: " + username);
    }

    public void removeSession(String sessionId) {
        String username = activeSessions.remove(sessionId);

        if (username != null) {
            serverLog.info("AUTH", "Usuário deslogado: " + username);
            // 🔴 AQUI É O PULO DO GATO:
            // "Ei LockService, o fulano saiu. Solte tudo que ele estava segurando!"
            lockService.releaseAllLocksByUser(username);
        }
    }

    public String getUser(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public boolean isUserConnected(String username) {
        return activeSessions.containsValue(username);
    }
}
