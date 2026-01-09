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
import java.util.concurrent.CompletableFuture;
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

    // --- CONTROLE DE INSTÂNCIAS (Único PC por usuário) ---
    // Mapeia Usuário -> ID da Instância Atual (ex: "admin" -> "uuid-pc-casa")
    private final Map<String, String> userInstanceMap = new ConcurrentHashMap<>();

    // Mapeia Usuário -> Conjunto de SessionIDs do WebSocket (ex: "admin" -> ["sess-app", "sess-sheet"])
    private final Map<String, Set<String>> userSessionsMap = new ConcurrentHashMap<>();

    // Mapeia SessionID -> Usuário (para facilitar lookup reverso)
    private final Map<String, String> sessionUserMap = new ConcurrentHashMap<>();

    // --- CONTROLE DE TEMPO (1h + Carência) ---
    // Mapeia SessionID -> Data de Expiração
    private final Map<String, Instant> sessionExpirations = new ConcurrentHashMap<>();
    // Mapeia SessionID -> Data Fim da Carência (se estiver em carência)
    private final Map<String, Instant> gracePeriods = new ConcurrentHashMap<>();

    public ActiveUserManager(CellLockService lockService, ServerLog serverLog, SimpMessagingTemplate messagingTemplate) {
        this.lockService = lockService;
        this.serverLog = serverLog;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Registra uma nova conexão WebSocket. Se vier com InstanceID diferente do
     * atual, marca as sessões antigas como inválidas.
     */
    public synchronized Set<String> registerConnection(String sessionId, String username, String incomingInstanceId, long accessMinutes) {
        String currentInstanceId = userInstanceMap.get(username);
        Set<String> sessionsToKick = new HashSet<>();

        // 1. Verifica se é um login de um NOVO lugar (PC diferente)
        if (currentInstanceId != null && !currentInstanceId.equals(incomingInstanceId)) {
            serverLog.warn("AUTH", "Nova instância detectada para " + username + ". Invalidando sessões anteriores.");

            // Pega as sessões do PC antigo para remover
            if (userSessionsMap.containsKey(username)) {
                sessionsToKick.addAll(userSessionsMap.get(username));

                // Limpa as sessões antigas do mapa de expiração e usuário
                for (String oldSession : sessionsToKick) {
                    sessionUserMap.remove(oldSession);
                    sessionExpirations.remove(oldSession);
                    gracePeriods.remove(oldSession);
                }
                userSessionsMap.get(username).clear();
            }
        }

        // 2. Atualiza a instância vigente
        userInstanceMap.put(username, incomingInstanceId);

        // 3. Registra a nova sessão
        userSessionsMap.computeIfAbsent(username, k -> new CopyOnWriteArraySet<>()).add(sessionId);
        sessionUserMap.put(sessionId, username);

        // 4. Define expiração (1h a partir de agora)
        sessionExpirations.put(sessionId, Instant.now().plusSeconds(accessMinutes * 60));

        // Log visual
        String agora = LocalTime.now().format(timeFormatter);
        // Só loga se for a primeira sessão do usuário (para não flodar com SheetSocket)
        if (userSessionsMap.get(username).size() == 1) {
            serverLog.info("LOGIN", String.format("Usuário: %s - Entrada: %s - Sessão: %d min", username, agora, accessMinutes));
            refreshPanel();
        }

        return sessionsToKick;
    }

    // Método legado para manter compatibilidade se necessário, mas o ideal é usar registerConnection
    public void addSession(String sessionId, String username, long accessMinutes) {
        // Assume instância desconhecida se não informado
        registerConnection(sessionId, username, "unknown", accessMinutes);
    }

    public void removeSession(String sessionId) {
        String username = sessionUserMap.remove(sessionId);

        // Remove dos mapas de tempo
        sessionExpirations.remove(sessionId);
        gracePeriods.remove(sessionId);

        if (username != null) {
            Set<String> sessions = userSessionsMap.get(username);
            if (sessions != null) {
                sessions.remove(sessionId);

                // Se não sobrou nenhuma sessão (usuário saiu totalmente), faz cleanup final
                if (sessions.isEmpty()) {
                    userSessionsMap.remove(username);
                    userInstanceMap.remove(username);

                    String agora = LocalTime.now().format(timeFormatter);
                    serverLog.info("LOGOUT", String.format("Usuário: %s - Saída Total: %s", username, agora));

                    // Libera locks apenas se saiu totalmente
                    lockService.releaseAllLocksByUser(username);
                    refreshPanel();
                }
            }
        }
    }

    @Scheduled(fixedRate = 5000) // Roda a cada 5s
    public void checkExpirations() {
        Instant now = Instant.now();
        Set<String> usersNotifiedThisCycle = ConcurrentHashMap.newKeySet();

        new HashMap<>(sessionExpirations).forEach((sessionId, expiration) -> {
            // [CORREÇÃO 1] Captura o username IMEDIATAMENTE antes da thread
            String username = sessionUserMap.get(sessionId);

            if (username == null) {
                return;
            }

            if (now.isAfter(expiration)) {
                // --- FASE 1: CARÊNCIA ---
                if (!gracePeriods.containsKey(sessionId)) {
                    gracePeriods.put(sessionId, Instant.now().plusSeconds(120));

                    // [CORREÇÃO 2] Passamos o username já capturado para a thread
                    final String finalUser = username;
                    CompletableFuture.runAsync(() -> {
                        if (usersNotifiedThisCycle.add(finalUser)) {
                            serverLog.info("AUTH", "Tempo esgotado para " + finalUser + ". Carência iniciada.");
                            // Agora o DTO terá o usuário preenchido!
                            sendForceLogoutCommand(finalUser, "RECONNECT_REQUIRED", "WARN");
                        }
                    });

                } else {
                    // --- FASE 2: KILL ---
                    if (now.isAfter(gracePeriods.get(sessionId))) {
                        final String finalUser = username;
                        CompletableFuture.runAsync(() -> {
                            try {
                                if (usersNotifiedThisCycle.add(finalUser)) {
                                    serverLog.info("AUTH", "🔪 Carência esgotada para " + finalUser + ". Enviando Kill.");
                                    sendForceLogoutCommand(finalUser, "FORCE_LOGOUT", "WARN");
                                    Thread.sleep(500);
                                }
                                forceDisconnect(sessionId);
                            } catch (Exception e) {
                                serverLog.error("AUTH", "Erro ao desconectar " + finalUser, e);
                            }
                        });
                    }
                }
            }
        });
    }

    public boolean renewSessionByUsername(String username, long accessMinutes) {
        Set<String> sessions = userSessionsMap.get(username);
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }

        boolean renewed = false;
        for (String sessionId : sessions) {
            // Renova por mais 60 min
            sessionExpirations.put(sessionId, Instant.now().plusSeconds(accessMinutes * 60));
            // Remove da carência
            if (gracePeriods.remove(sessionId) != null) {
                renewed = true;
                serverLog.info("AUTH", "Sessão salva da carência: " + sessionId + " user: " + username);
            }
        }

        if (renewed) {
            serverLog.info("AUTH", "Sessão renovada via API para: " + username);
        }
        return true; // Retorna true se encontrou sessões ativas
    }

    public void forceDisconnect(String sessionId) {
        String username = sessionUserMap.get(sessionId);
        if (username != null) {
            sendForceLogoutCommand(username, "FORCE_LOGOUT", "WARN");
            removeSession(sessionId);
        }
    }

    private void sendForceLogoutCommand(String username, String message, String level) {
        // [LOG DE TESTE]
        System.out.println(">>> MONTANDO COMANDO WS: User=" + username + " Msg=" + message);

        try {
            LogDto dto = new LogDto();
            dto.setLevel(level);
            dto.setContext("AUTH");
            dto.setMessage(message);
            dto.setUser(username); // <--- Isso aqui não pode ser null!
            dto.setTimestamp(java.time.LocalDateTime.now().toString());

            // Importante: O destino deve ser exatamente o que o cliente assina (/user/topic/errors)
            messagingTemplate.convertAndSendToUser(username, "/topic/errors", dto);
        } catch (Exception e) {
            serverLog.error("AUTH", "Falha ao enviar comando para " + username, e);
        }
    }

    public boolean isUserInGracePeriod(String username) {
        // Verifica se algum sessionId deste usuário está no mapa de carência
        Set<String> sessions = userSessionsMap.get(username);
        if (sessions == null) {
            return false;
        }
        return sessions.stream().anyMatch(gracePeriods::containsKey);
    }

    // --- Helpers Visuais ---
    private synchronized void refreshPanel() {
        // (Seu código de print no console)
        int totalUsers = userSessionsMap.size();
        System.out.println("\n==========================================");
        System.out.println(" USUÁRIOS ATIVOS (Únicos): " + totalUsers);
        // serverLog.info("USUÁRIOS", "ONLINE: " + totalUsers); // Opcional
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

    // Método auxiliar para o Interceptor verificar se a sessão ainda é válida (para o PC antigo)
    public boolean isSessionValid(String sessionId) {
        return sessionUserMap.containsKey(sessionId);
    }

    public int getSessionCount(String username) {
        Set<String> sessions = userSessionsMap.get(username);
        return (sessions == null) ? 0 : sessions.size();
    }
}
