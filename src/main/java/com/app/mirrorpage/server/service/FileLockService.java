package com.app.mirrorpage.server.service;

import com.app.mirrorpage.api.dto.FileLockEvent;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Service
public class FileLockService {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 1. Classe interna para guardar Dono + Validade
    private static class FileLockInfo {

        final String owner;
        final Instant expiresAt;

        FileLockInfo(String owner, Instant expiresAt) {
            this.owner = owner;
            this.expiresAt = expiresAt;
        }
    }

    // 2. Mapa agora guarda o OBJETO FileLockInfo, não apenas a String
    private final Map<String, FileLockInfo> locks = new ConcurrentHashMap<>();

    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.of("America/Sao_Paulo"));

    // Tempo de vida do lock (2 minutos)
    private static final Duration TTL = Duration.ofMinutes(5);

    private final ServerLog serverLog;

    public FileLockService(ServerLog serverLog) {
        this.serverLog = serverLog;
    }

    public synchronized boolean tryLock(String path, String user) {
        Instant now = Instant.now();
        FileLockInfo existing = locks.get(path);

        // Se existe lock
        if (existing != null) {
            // Se expirou -> Remove e deixa pegar
            if (existing.expiresAt.isBefore(now)) {
                locks.remove(path);
            } // Se NÃO expirou e é de OUTRO usuário -> Bloqueia
            else if (!existing.owner.equals(user)) {
                return false;
            }
        }

        // 2. Criar ou Renovar o lock (neste ponto, ou estava livre ou era do mesmo usuário)
        FileLockInfo newLock = new FileLockInfo(user, now.plus(TTL));
        locks.put(path, newLock);

        // 3. Formatar a data usando o NOVO lock (newLock), que nunca é nulo aqui
        String expiraEmLocal = LOCAL_FMT.format(newLock.expiresAt);

        serverLog.warn("LOCK", "Concedido para o " + user + " em " + path + " Expira em: " + expiraEmLocal);
        return true;
    }

    public synchronized void unlock(String path, String user) {
        FileLockInfo lock = locks.get(path);
        // Só remove se existir e for do usuário solicitante
        if (lock != null && lock.owner.equals(user)) {
            locks.remove(path);
            serverLog.warn("LOCK", "Liberado por " + user + " em " + path);
        }
    }

    public String getOwner(String path) {
        FileLockInfo lock = locks.get(path);
        if (lock == null) {
            return null;
        }

        // Se expirou, limpa e retorna null
        if (lock.expiresAt.isBefore(Instant.now())) {
            locks.remove(path);
            return null;
        }
        return lock.owner;
    }

    public boolean isOwner(String path, String user) {
        FileLockInfo lock = locks.get(path);

        if (lock == null) {
            return false;
        }

        if (lock.expiresAt.isBefore(Instant.now())) {
            locks.remove(path);
            return false;
        }

        return lock.owner.equals(user);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 5000)
    public void broadcastExpiredFileLocks() {
        Instant now = Instant.now();
        locks.forEach((path, info) -> {
            if (info.expiresAt.isBefore(now)) {
                locks.remove(path);
                serverLog.warn("FILE LOCK", "Lauda expirada: " + path);
                // Avisa que o arquivo está LIVRE (locked = false)
                messagingTemplate.convertAndSend("/topic/locks",
                        new FileLockEvent(path, null, false, false));
            }
        });
    }
}
