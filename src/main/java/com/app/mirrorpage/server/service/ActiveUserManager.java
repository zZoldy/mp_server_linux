package com.app.mirrorpage.server.service;

import com.app.mirrorpage.server.tabel.CellLockService;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class ActiveUserManager {

    // Injeção do serviço de Locks para poder limpar quando o usuário sair
    private final CellLockService lockService;

    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Instant> sessionExpirations = new ConcurrentHashMap<>();

    private final Map<String, Instant> gracePeriods = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messagingTemplate;

    private final ServerLog serverLog;

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    public ActiveUserManager(CellLockService lockService, ServerLog serverLog, SimpMessagingTemplate messagingTemplate) {
        this.lockService = lockService;
        this.serverLog = serverLog;
        this.messagingTemplate = messagingTemplate;
    }

    public void addSession(String sessionId, String username, long accessMinutes) {
        activeSessions.put(sessionId, username);
        sessionExpirations.put(sessionId, Instant.now().plusSeconds(accessMinutes * 60));

        // Captura o horário atual no formato HH:mm:ss
        String agora = LocalTime.now().format(timeFormatter);

        serverLog.info("LOGIN", String.format("Usuário: %s - Entrada: %s - Sessão: %d min",
                username, agora, accessMinutes));

        refreshPanel();
    }

    public void forceDisconnect(String sessionId) {
        String username = activeSessions.get(sessionId);
        if (username != null) {
            // 1. Enviamos o LOG público (Todos veem no console, mas ninguém desloga)
            serverLog.info("SISTEMA", "Sessão expirada para: " + username);

            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "FORCE_LOGOUT");
                payload.put("user", username);
                payload.put("reason", "Sessão expirada");

                messagingTemplate.convertAndSend("/topic/logs", payload);
            } catch (Exception e) {
                serverLog.error("AUTH", "Falha ao enviar kick para " + username, e);

            }
            removeSession(sessionId);
        }
    }

    public void removeSession(String sessionId) {
        String username = activeSessions.remove(sessionId);
        sessionExpirations.remove(sessionId);

        if (username != null) {
            String agora = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            serverLog.info("LOGOUT", String.format("Usuário: %s - Saída: %s", username, agora));
            lockService.releaseAllLocksByUser(username);
            refreshPanel();
        }
    }

    private synchronized void refreshPanel() {
        // Limpa o console e volta o cursor para o topo
        System.out.println("\n==========================================");
        System.out.println(" USUÁRIOS ONLINE: " + activeSessions.size());
        serverLog.info("USUÁRIOS", "ONLINE: " + activeSessions.size());
        System.out.println("------------------------------------------");

        if (activeSessions.isEmpty()) {
            System.out.println(" > Nenhum usuário conectado.");
        } else {
            // Distinct para evitar duplicar nome se houver múltiplas sessões do mesmo user
            activeSessions.values().stream().distinct().forEach(user -> {
                System.out.println(" [+] " + user);
            });
        }
        System.out.println("==========================================\n");

    }

    @Scheduled(fixedRate = 5000)
    public void checkExpirations() {
        Instant now = Instant.now();
        sessionExpirations.forEach((sessionId, expiration) -> {
            if (now.isAfter(expiration)) {
                String username = activeSessions.get(sessionId);

                // Se ainda não estiver em carência, inicia o cronómetro de 2 minuto
                if (!gracePeriods.containsKey(sessionId)) {
                    serverLog.info("RECONNECT_REQUIRED", "Tempo de carência para o usuário: " + username, username);
                    gracePeriods.put(sessionId, Instant.now().plusSeconds(120)); // 60 segundos de tolerância

                    // Envia notificação para o cliente via WebSocket
                    messagingTemplate.convertAndSend("/topic/logs", forceLogoutPayload(username, "RECONNECT_REQUIRED", "FORCE_RECONNECT"));
                } else {
                    // Se já passou o 2 minuto de carência sem resposta
                    if (now.isAfter(gracePeriods.get(sessionId))) {
                        serverLog.info("SISTEMA", "Tempo de carência esgotado para: " + username);
                        forceDisconnect(sessionId);
                        gracePeriods.remove(sessionId);
                    }
                }
            }
        });
    }

    public String getUser(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public boolean isUserConnected(String username) {
        return activeSessions.containsValue(username);
    }

    private Map<String, Object> forceLogoutPayload(String username, String reason, String type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", type);
        payload.put("user", username);
        payload.put("reason", reason);
        payload.put("ts", Instant.now().toString()); // opcional (debug)
        return payload;
    }

    // Adicione este método para renovar a sessão quando o cliente responder
    public void renewSession(String sessionId) {
        String username = activeSessions.get(sessionId);
        if (username != null) {
            // Renova por mais 60 minutos (1 hora)
            sessionExpirations.put(sessionId, Instant.now().plusSeconds(60 * 60));
            // Remove do período de carência
            gracePeriods.remove(sessionId);
            serverLog.info("AUTH", "Sessão renovada com sucesso para: " + username);
        }
    }

    public boolean renewSessionByUsername(String username) {
        // Procura a sessão associada a este usuário
        for (Map.Entry<String, String> entry : activeSessions.entrySet()) {
            if (entry.getValue().equals(username)) {
                String sessionId = entry.getKey();

                // 1. Dá mais 1 hora de vida (60 min)
                sessionExpirations.put(sessionId, Instant.now().plusSeconds(60 * 60));

                // 2. ✅ REMOVE da carência para não ser expulso pelo @Scheduled
                gracePeriods.remove(sessionId);

                return true;
            }
        }
        return false;
    }
}
