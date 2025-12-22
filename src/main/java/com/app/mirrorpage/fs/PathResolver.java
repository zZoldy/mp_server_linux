/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.fs;

import com.app.mirrorpage.server.service.ServerLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PathResolver {

    private final Path root;
    private final ServerLog serverLog;

    public PathResolver(@Value("${mirrorpage.root}") String rootDir, ServerLog serverLog) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        this.serverLog = serverLog;

        // --- CRIAÇÃO AUTOMÁTICA DA PASTA 'PRODUTOS' ---
        try {
            if (!Files.exists(this.root)) {
                Files.createDirectories(this.root); // Cria Produtos e mirrorpage se não existirem
                serverLog.info("PathReolver", "Pasta raiz criada com sucesso em: " + this.root);
            }
        } catch (IOException e) {
            // Se não der pra criar a pasta raiz, o servidor nem deve subir
            serverLog.error("PathReolver", "ERRO CRÍTICO: Não foi possível criar o diretório raiz: " + rootDir, e);
            throw new RuntimeException("ERRO CRÍTICO: Não foi possível criar o diretório raiz: " + rootDir, e);
        }
    }

    public Path getRoot() {
        return root;
    }

    public Path resolveSafe(String apiPath) {
        if (apiPath == null || apiPath.isBlank() || "/".equals(apiPath)) {
            return root;
        }

        try {
            apiPath = java.net.URLDecoder.decode(apiPath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Logar erro ou ignorar se tiver certeza que não precisa
        }

        String clean = apiPath.replace("\\", "/");
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }

        Path p = root.resolve(clean).normalize().toAbsolutePath();

        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Caminho fora da raiz");
        }
        return p;
    }

    // Adicione isto na classe PathResolver
    public static String toTopic(String path) {
        if (path == null) {
            return "";
        }
        // Troca barra invertida por normal
        String s = path.trim().replace("\\", "/");

        // Se começar com /, remove (para não ficar // no tópico)
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }
}
