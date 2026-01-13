package com.app.mirrorpage.server.service;

import com.app.mirrorpage.api.dto.LogDto;
import com.app.mirrorpage.server.tabel.CellLockService;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ActiveUserManager {

    private final CellLockService lockService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ServerLog serverLog;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    // --- INSTÂNCIA ÚNICA POR USUÁRIO ---
    private final Map<String, String> userInstanceMap = new ConcurrentHashMap<>();              // user -> instanceId atual
    private final Map<String, Set<String>> userSessionsMap = new ConcurrentHashMap<>();         // user -> sessionIds
    private final Map<String, String> sessionUserMap = new ConcurrentHashMap<>();               // sessionId -> user

    // --- TEMPO POR USUÁRIO (NÃO por sessão) ---
    private final Map<String, Instant> userExpirations = new ConcurrentHashMap<>();             // user -> expira em
    private final Map<String, Instant> userGraceUntil = new ConcurrentHashMap<>();             // user -> carência até
    private final Set<String> graceWarnSent = ConcurrentHashMap.newKeySet();                    // evita spam

    public ActiveUserManager(CellLockService lockService, ServerLog serverLog, SimpMessagingTemplate messagingTemplate) {
        this.lockService = lockService;
        this.serverLog = serverLog;
        this.messagingTemplate = messagingTemplate;
    }

    public synchronized Set<String> registerConnection(String sessionId, String username, String incomingInstanceId, long accessMinutes) {
        String currentInstanceId = userInstanceMap.get(username);
        Set<String> sessionsToKick = new HashSet<>();

        // 1) NOVA INSTÂNCIA (PC diferente) => derruba TUDO do usuário antigo
        if (currentInstanceId != null && !currentInstanceId.equals(incomingInstanceId)) {
            serverLog.warn("AUTH", "Nova instância detectada para " + username + ". Derrubando instância anterior.");
            System.out.println("[ActiveUserManager] U: " + username + " ID: " + incomingInstanceId);

            // captura sessões antigas para log (opcional)
            Set<String> oldSessions = userSessionsMap.get(username);
            if (oldSessions != null) {
                sessionsToKick.addAll(oldSessions);
            }

            // avisa o cliente antigo UMA VEZ
            sendCommand(username, "FORCE_LOGOUT:NEW_LOGIN", "WARN");

            // remove tudo do usuário (sessions/maps/locks)
            hardDisconnectUser(username);
        }

        // 2) Atualiza instância vigente
        userInstanceMap.put(username, incomingInstanceId);

        // 3) Registra nova sessão
        userSessionsMap.computeIfAbsent(username, k -> new CopyOnWriteArraySet<>()).add(sessionId);
        sessionUserMap.put(sessionId, username);

        // 4) Expiração por USUÁRIO: renova a cada conexão (ou mantenha só no login, você escolhe)
        userExpirations.put(username, Instant.now().plusSeconds(accessMinutes * 60));
        userGraceUntil.remove(username);
        graceWarnSent.remove(username);

        // Log só na primeira sessão (pra não flodar)
        String agora = LocalTime.now().format(timeFormatter);
        if (userSessionsMap.get(username).size() == 1) {
            serverLog.info("LOGIN", String.format("Usuário: %s - Entrada: %s - Sessão: %d min", username, agora, accessMinutes));
            refreshPanel();
        }

        return sessionsToKick;
    }

    // compat
    public void addSession(String sessionId, String username, String incomingInstanceId, long accessMinutes) {
        registerConnection(sessionId, username, incomingInstanceId, accessMinutes);
    }

    /**
     * Remove apenas UMA sessão WS. Só faz cleanup total quando não sobrar
     * nenhuma.
     */
    public synchronized void removeSession(String sessionId) {
        String username = sessionUserMap.remove(sessionId);
        if (username == null) {
            return;
        }

        Set<String> sessions = userSessionsMap.get(username);
        if (sessions != null) {
            sessions.remove(sessionId);

            if (sessions.isEmpty()) {
                // saiu totalmente
                userSessionsMap.remove(username);
                userInstanceMap.remove(username);
                userExpirations.remove(username);
                userGraceUntil.remove(username);
                graceWarnSent.remove(username);

                String agora = LocalTime.now().format(timeFormatter);
                serverLog.info("LOGOUT", String.format("Usuário: %s - Saída Total: %s", username, agora));

                lockService.releaseAllLocksByUser(username);
                refreshPanel();
            }
        }
    }

    /**
     * Scheduler central por USUÁRIO.
     */
    @Scheduled(fixedRate = 5000)
    public void checkExpirations() {
        Instant now = Instant.now();

        new HashMap<>(userExpirations).forEach((username, expiration) -> {
            if (username == null) {
                return;
            }

            // se usuário já não tem sessões, limpa
            if (!userSessionsMap.containsKey(username)) {
                userExpirations.remove(username);
                userGraceUntil.remove(username);
                graceWarnSent.remove(username);
                return;
            }

            if (now.isBefore(expiration)) {
                return;
            }

            // --- FASE 1: CARÊNCIA ---
            if (!userGraceUntil.containsKey(username)) {
                userGraceUntil.put(username, now.plusSeconds(120));

                if (graceWarnSent.add(username)) {
                    serverLog.info("AUTH", "Tempo esgotado para " + username + ". Carência iniciada (2min).");
                    sendCommand(username, "RECONNECT_REQUIRED", "WARN");
                }
                return;
            }

            // --- FASE 2: KILL ---
            Instant graceEnd = userGraceUntil.get(username);
            if (graceEnd != null && now.isAfter(graceEnd)) {
                serverLog.warn("AUTH", "🔪 Carência esgotada para " + username + ". Forçando logout.");
                sendCommand(username, "FORCE_LOGOUT:INACTIVITY_TIMEOUT", "WARN");

                // remove tudo do usuário (locks inclusos)
                hardDisconnectUser(username);
            }
        });
    }

    public synchronized boolean renewSessionByUsername(String username, long accessMinutes) {
        Set<String> sessions = userSessionsMap.get(username);
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }

        userExpirations.put(username, Instant.now().plusSeconds(accessMinutes * 60));
        userGraceUntil.remove(username);
        graceWarnSent.remove(username);

        serverLog.info("AUTH", "Sessão renovada via API para: " + username);
        return true;
    }

    /**
     * Remove tudo do usuário: sessões, mapas, carência e LOCKS. NÃO tenta
     * mandar outro comando aqui (manda antes, se precisar).
     */
    private synchronized void hardDisconnectUser(String username) {
        Set<String> sessions = userSessionsMap.get(username);
        if (sessions != null) {
            for (String sid : new HashSet<>(sessions)) {
                sessionUserMap.remove(sid);
            }
        }

        userSessionsMap.remove(username);
        userInstanceMap.remove(username);
        userExpirations.remove(username);
        userGraceUntil.remove(username);
        graceWarnSent.remove(username);

        lockService.releaseAllLocksByUser(username);
        refreshPanel();
    }

    private void sendCommand(String username, String message, String level) {
        try {
            LogDto dto = new LogDto();
            dto.setLevel(level);
            dto.setContext("AUTH");
            dto.setMessage(message);
            dto.setUser(username);
            dto.setTimestamp(java.time.LocalDateTime.now().toString());

            messagingTemplate.convertAndSendToUser(username, "/topic/errors", dto);
        } catch (Exception e) {
            serverLog.error("AUTH", "Falha ao enviar comando para " + username, e);
        }
    }

    public boolean isUserInGracePeriod(String username) {
        return userGraceUntil.containsKey(username);
    }

    private synchronized void refreshPanel() {
        int totalUsers = userSessionsMap.size();
        System.out.println("\n==========================================");
        System.out.println(" USUÁRIOS ATIVOS (Únicos): " + totalUsers);
        System.out.println("------------------------------------------");
        if (userSessionsMap.isEmpty()) {
            System.out.println(" > Nenhum usuário conectado.");
        } else {
            userSessionsMap.keySet().forEach(user -> System.out.println(" [+] " + user));
        }
        System.out.println("==========================================\n");
    }

    public boolean isUserConnected(String username) {
        return userSessionsMap.containsKey(username);
    }

    public boolean isSessionValid(String sessionId) {
        return sessionUserMap.containsKey(sessionId);
    }

    public int getSessionCount(String username) {
        Set<String> sessions = userSessionsMap.get(username);
        return (sessions == null) ? 0 : sessions.size();
    }
}
