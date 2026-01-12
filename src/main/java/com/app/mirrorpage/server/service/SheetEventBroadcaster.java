/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.service;

import com.app.mirrorpage.api.dto.StopwatchEvent;
import com.app.mirrorpage.server.tabel.RowDeletedEvent;
import com.app.mirrorpage.server.tabel.RowMoveEvent;
import com.app.mirrorpage.server.tabel.SheetCellChangeEvent;
import com.app.mirrorpage.server.tabel.SheetRowInsertedEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class SheetEventBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public SheetEventBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendCellChange(SheetCellChangeEvent ev) {
        String topic = "/topic/sheet/" + toTopic(ev.path());
        messagingTemplate.convertAndSend(topic, ev);
    }

    public void sendRowInserted(SheetRowInsertedEvent ev) {
        // **DICA IMPORTANTE**:
        // use OUTRO tópico para não misturar JSON de tipos diferentes
        String topic = "/topic/sheet/" + toTopic(ev.path());
        messagingTemplate.convertAndSend(topic, ev);
    }

    public void sendRowMoved(RowMoveEvent ev) {
        String topic = "/topic/sheet/" + toTopic(ev.path());
        messagingTemplate.convertAndSend(topic, ev);
    }

    public void sendRowDeleted(RowDeletedEvent ev) {
        // 1. Define o tópico de destino (mesma lógica dos outros)
        String topic = "/topic/sheet/" + toTopic(ev.path());

        // 2. Envia o objeto (o Record será serializado para JSON automaticamente)
        messagingTemplate.convertAndSend(topic, ev);
    }

    private String toTopic(String path) {
        // Mesmo esquema que você já usa (tirar barras, espaços etc.)
        return path.replace("\\", "/").replace("/", "_");
    }

    public void sendSheetRestored(String path, String user) {
        String topic = "/topic/sheet/" + toTopic(path);

        // Cria um payload anônimo ou um DTO específico
        // Importante: "isRestore" ajuda o cliente a diferenciar de outros eventos
        var event = new java.util.HashMap<String, Object>();
        event.put("path", path);
        event.put("user", user);
        event.put("isRestore", true);

        messagingTemplate.convertAndSend(topic, event);
    }

    // 👇 ADICIONE ESTE MÉTODO 👇
    public void sendStopwatchEvent(StopwatchEvent ev) {
        // Normaliza o path para criar o tópico (ex: /topic/sheet/_BDBR_Prelim.csv)
        // Isso deve bater com a lógica do seu SheetSocketClient no Java
        String topicId = toTopic(ev.getPath());

        String destination = "/topic/sheet/" + topicId;

        messagingTemplate.convertAndSend(destination, ev);
    }
}
