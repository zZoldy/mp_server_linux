/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.service;

import com.app.mirrorpage.api.dto.LogDto;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 *
 * @author Z D K
 */
@Service
public class ServerLog {

    private final SimpMessagingTemplate messagingTemplate;
    private final String logFilePath;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ServerLog(@Lazy SimpMessagingTemplate messagingTemplate,
            @Value("${app.log.path:ServerLogs.txt}") String logFilePath) {
        this.messagingTemplate = messagingTemplate;
        this.logFilePath = logFilePath;
        // --- CRIAÇÃO AUTOMÁTICA DA PASTA 'CONF' ---
        try {
            File arquivoLog = new File(logFilePath);
            File pastaConf = arquivoLog.getParentFile();

            // Se o caminho for apenas "ServerLogs.txt", parent é null (pasta local), então ignoramos
            if (pastaConf != null && !pastaConf.exists()) {
                if (pastaConf.mkdirs()) { // .mkdirs() cria C:/mirrorpage/Conf de uma vez só
                    System.out.println("ServerLog: Pasta de logs criada em: " + pastaConf.getAbsolutePath());
                } else {
                    System.err.println("ServerLog: Falha ao tentar criar a pasta de logs: " + pastaConf.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            System.err.println("Aviso: Erro ao verificar diretório de logs: " + e.getMessage());
        }
    }

    public List<LogDto> getHistory(int limit) {
        File f = new File(this.logFilePath);
        if (!f.exists()) {
            return Collections.emptyList();
        }

        // Usamos LinkedList para adicionar no final de forma eficiente
        LinkedList<LogDto> logs = new LinkedList<>();

        // Regex para quebrar a linha: [DATA] [NIVEL] [CONTEXTO] MENSAGEM
        Pattern pattern = Pattern.compile("^\\[(.*?)\\] \\[(.*?)\\] \\[(.*?)\\] (.*)$");

        try {
            List<String> lines = Files.readAllLines(Paths.get(this.logFilePath));

            LogDto current = null;
            StringBuilder stackBuffer = new StringBuilder();

            for (String line : lines) {
                Matcher m = pattern.matcher(line);

                if (m.find()) {
                    // Se já tínhamos um log anterior sendo montado, salva ele na lista
                    if (current != null) {
                        if (stackBuffer.length() > 0) {
                            current.setStackTrace(stackBuffer.toString());
                        }
                        logs.add(current);
                        stackBuffer.setLength(0);
                    }

                    // Cria novo Log (Usando a CLASSE do Servidor, que tem setters)
                    current = new LogDto();
                    current.setTimestamp(m.group(1));
                    current.setLevel(m.group(2));
                    current.setContext(m.group(3));
                    current.setMessage(m.group(4));
                    current.setStackTrace(""); // Inicializa vazio
                } else {
                    // É continuação (Stack Trace)
                    if (current != null) {
                        stackBuffer.append(line).append("\n");
                    }
                }
            }
            // Adiciona o último registro pendente
            if (current != null) {
                if (stackBuffer.length() > 0) {
                    current.setStackTrace(stackBuffer.toString());
                }
                logs.add(current);
            }

        } catch (IOException e) {
            System.err.println("Erro ao ler histórico: " + e.getMessage());
        }

        // Retorna apenas os últimos 'limit' registros
        if (logs.size() > limit) {
            // Sublist cria uma view, passamos para ArrayList para serializar seguro
            return new java.util.ArrayList<>(logs.subList(logs.size() - limit, logs.size()));
        }
        return logs;
    }

    public void info(String contexto, String mensagem) {
        registrar("INFO", contexto, mensagem, null);
    }

    public void error(String contexto, String mensagem, Throwable t) {
        registrar("ERROR", contexto, mensagem, t);
    }

    public void warn(String contexto, String mensagem) {
        registrar("WARN", contexto, mensagem, null);
    }

    private void registrar(String nivel, String contexto, String mensagem, Throwable t) {
        String ts = LocalDateTime.now().format(FMT);
        String stackTrace = "";

        if (t != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            stackTrace = sw.toString();
        }

// 1. WebSocket
        try {
            messagingTemplate.convertAndSend("/topic/logs", new LogDto(ts, nivel, contexto, mensagem, stackTrace));
        } catch (Exception ignored) {
        }

// 2. Arquivo e Console
        String logLine = String.format("[%s] [%s] [%s] %s", ts, nivel, contexto, mensagem);

        try (FileWriter fw = new FileWriter(this.logFilePath, true)) {
            fw.write(logLine + System.lineSeparator());
            if (t != null) {
                fw.write(stackTrace + System.lineSeparator());
            }
        } catch (IOException e) {
            System.err.println("ERRO CRÍTICO LOG: " + e.getMessage());
        }
    }
}
