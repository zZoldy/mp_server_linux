package com.app.mirrorpage.server.service;

import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FileLockService {

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

    // Tempo de vida do lock (2 minutos)
    private static final Duration TTL = Duration.ofMinutes(2);

    private final ServerLog serverLog;

    public FileLockService(ServerLog serverLog) {
        this.serverLog = serverLog;
    }

    /**
     * Tenta aplicar o lock. Retorna TRUE se conseguiu.
     */
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

        // Cria ou Renova o lock
        locks.put(path, new FileLockInfo(user, now.plus(TTL)));

        serverLog.warn("FILE LOCK", "[LOCK] concedido/renovado para " + user + " em " + path);
        return true;
    }

    /**
     * Libera o lock se o usuário for o dono.
     */
    public synchronized void unlock(String path, String user) {
        FileLockInfo lock = locks.get(path);
        // Só remove se existir e for do usuário solicitante
        if (lock != null && lock.owner.equals(user)) {
            locks.remove(path);
            serverLog.warn("FILE LOCK", "[LOCK] liberado por " + user + " em " + path);
        }
    }

    /**
     * Retorna o nome do dono atual (ou null se livre).
     */
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

    /**
     * Verifica se o usuário é o dono legítimo do lock atual. Usado pelo
     * endpoint de notificação (CTRL+S).
     */
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
}
