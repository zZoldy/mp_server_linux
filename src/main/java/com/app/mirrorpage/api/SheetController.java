package com.app.mirrorpage.api;

import com.app.mirrorpage.api.dto.DeleteRowRequest;
import com.app.mirrorpage.api.dto.MoveRowRequest;
import com.app.mirrorpage.api.dto.PromoteRequest;
import com.app.mirrorpage.fs.PathResolver;
import com.app.mirrorpage.server.domain.user.User; // 1. Importe sua entidade User
import com.app.mirrorpage.server.service.ServerLog;
import com.app.mirrorpage.server.service.SheetEventBroadcaster;
import com.app.mirrorpage.server.tabel.CellLock;
import com.app.mirrorpage.server.tabel.CellLockRequest;
import com.app.mirrorpage.server.tabel.CellLockResponse;
import com.app.mirrorpage.server.tabel.CellLockService;
import com.app.mirrorpage.server.tabel.CellSaveRequest;
import com.app.mirrorpage.server.tabel.SheetCellChangeEvent;
import com.app.mirrorpage.server.tabel.SheetRowInsertedEvent;
import com.app.mirrorpage.server.tabel.SheetService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal; // 2. Importe a anotação
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheet")
public class SheetController {

    private final PathResolver resolver;
    private final CellLockService lockService;
    private final SheetService sheetService;
    private final SheetEventBroadcaster sheetEventBroadcaster;
    private final ServerLog serverLog;

    public SheetController(CellLockService lockService,
            PathResolver resolver, SheetService sheetService,
            SheetEventBroadcaster sheetEventBroadcaster,
            ServerLog serverLog) {
        this.lockService = lockService;
        this.resolver = resolver;
        this.sheetService = sheetService;
        this.sheetEventBroadcaster = sheetEventBroadcaster;
        this.serverLog = serverLog;
    }

    // --- LOCK ---
    @PostMapping("/lock")
    public ResponseEntity<?> lock(@RequestBody CellLockRequest req,
            @AuthenticationPrincipal User user) { // 3. Use @AuthenticationPrincipal

        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        String username = user.getUsername(); // Garante o nome limpo ("admin")

        CellLock lock = lockService.acquire(req.path(), req.row(), req.col(), username);
        if (lock == null) {
            String owner = lockService.getOwner(req.path(), req.row(), req.col());

            serverLog.warn("SheetControoler", "[LOCK] RECUSADO Origem: " + req.path() + " - Linha: " + req.row() + " - Coluna: " + req.col() + " - Dono do Lock: " + owner + " - Usuário Barrado: " + username);

            record LockConflictResponse(String message, String owner) {

            }
            return ResponseEntity.status(409).body(new LockConflictResponse("Cell already locked", owner));
        }

        return ResponseEntity.ok(new CellLockResponse(
                lock.path, lock.row, lock.col, lock.owner, lock.expiresAt
        ));
    }

    // --- UNLOCK ---
    @PostMapping("/unlock")
    public ResponseEntity<?> unlock(@RequestBody CellLockRequest req,
            @AuthenticationPrincipal User user) {

        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        String username = user.getUsername();

        // Verifica se é dono
        if (!lockService.isOwner(req.path(), req.row(), req.col(), username)) {
            serverLog.warn("SheetControoler", "[LOCK] UNLOCK-NEGADO: " + req.path() + " - Linha: " + req.row() + " - Coluna: " + req.col() + " - Usuário: " + username);
            return ResponseEntity.status(403).body("Not lock owner");
        }

        lockService.release(req.path(), req.row(), req.col(), username);
        serverLog.warn("SheetController", "[LOCK] UNLOCK-DESBLOQUEADO Origem: " + req.path() + " - Linha: " + req.row() + " - Coluna: " + req.col() + " - Pelo Usuário: " + username);

        return ResponseEntity.ok().build();
    }

    // --- SAVE CELL ---
    @PostMapping("/save-cell")
    public ResponseEntity<?> saveCell(@RequestBody CellSaveRequest req,
            @AuthenticationPrincipal User user) {

        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        String username = user.getUsername();

        int modelRow = req.row();
        int col = req.col();

        try {
            Path filePath = resolver.resolveSafe(req.path());
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            List<String> linhas = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int fileRow = modelRow + 1; // Pula cabeçalho

            if (fileRow < 0 || fileRow >= linhas.size()) {
                return ResponseEntity.badRequest().body("Row out of bounds");
            }

            // Lógica de manipulação do CSV (Mantida igual a sua)
            String linha = linhas.get(fileRow);
            String sep = ";";
            String[] cols = linha.split(java.util.regex.Pattern.quote(sep), -1);

            if (col < 0) {
                return ResponseEntity.badRequest().body("Col out of bounds");
            }
            if (col >= cols.length) {
                // Expande array
                String[] novo = new String[col + 1];
                System.arraycopy(cols, 0, novo, 0, cols.length);
                for (int i = cols.length; i < novo.length; i++) {
                    novo[i] = "";
                }
                cols = novo;
            }

            cols[col] = (req.value() != null) ? req.value() : "";
            linhas.set(fileRow, String.join(sep, cols));
            Files.write(filePath, linhas, StandardCharsets.UTF_8);

            // 🔔 Broadcaster: Passamos o nome limpo (username), não o objeto User
            SheetCellChangeEvent evt = new SheetCellChangeEvent(
                    req.path(), modelRow, col, cols[col], username
            );
            sheetEventBroadcaster.sendCellChange(evt);

            serverLog.warn("SheetControoler", "[SAVE-CELL] Origem: " + req.path() + " - Linha: " + modelRow + " - Coluna: " + col + " - Usuário: " + username);
            return ResponseEntity.ok().build();

        } catch (Exception ex) {
            serverLog.error("SheetController", "Erro ao salvar céluar", ex);
            return ResponseEntity.internalServerError().body("Erro ao salvar célula");
        }
    }

    // --- OUTROS MÉTODOS (LOAD, INSERT, MOVE, DELETE) ---
    // Mantém a lógica, mas idealmente use @AuthenticationPrincipal também
    @GetMapping
    public ResponseEntity<String> loadSheet(@RequestParam("path") String path) throws IOException {
        String relPath = path.replaceFirst("^[\\\\/]+", "");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(sheetService.loadSheet(relPath));
    }

    @PostMapping("/row/insert")
    public ResponseEntity<Void> insertRow(@RequestParam("path") String path,
            @RequestParam("afterRow") int afterRow,
            @AuthenticationPrincipal User user) throws IOException {

        String username = (user != null) ? user.getUsername() : "unknown";

        sheetService.insertRow(path, afterRow, username);
        serverLog.warn("SheetControoler", "[rowInsert] Origem: " + path + " - Linha inserida: " + afterRow + " - Usuário: " + username);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/moveRow")
    public ResponseEntity<?> moveRow(@RequestBody MoveRowRequest req, @AuthenticationPrincipal User user) {
        try {
            // Preferência: usar o usuário do token, se disponível
            String username = (user != null) ? user.getUsername() : req.user();

            sheetService.moveRow(req.path(), req.from(), req.to(), username);
            serverLog.warn("SheetControoler", "[moveRow] Origem: " + req.path() + " - Linha Atual: " + req.from() + " - Linha enviada para: " + req.to() + " - Usuário: " + username);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            serverLog.warn("SheetController", "[moveRow] Erro ao mover linha");
            return ResponseEntity.status(409).body(e.getMessage());
        } catch (Exception e) {
            serverLog.error("SheetController", "[moveRow] Erro ao mover linha - >", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/deleteRow")
    public ResponseEntity<?> deleteRow(@RequestBody DeleteRowRequest req, @AuthenticationPrincipal User user) {
        try {
            String username = (user != null) ? user.getUsername() : req.user();

            sheetService.deleteRow(req.path(), req.row(), username);
            serverLog.warn("SheetControoler", "[deleteRow] Origem: " + req.path() + " - Linha deletada: " + req.row() + " - Usuário: " + username);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            serverLog.error("SheetController", "[deleteRow] Erro ao deletar linha - >", e);
            return ResponseEntity.status(409).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            serverLog.warn("SheetController", "[deleteRow] Erro ao deletar linha: " + e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            serverLog.error("SheetController", "[deleteRow] Erro ao deletar linha - >", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/pastas")
    public ResponseEntity<List<String>> getPastas() {
        return ResponseEntity.ok(sheetService.listarPastasRaiz());
    }

    @PostMapping("/copy-to-final")
    public ResponseEntity<?> copyToFinal(@RequestBody PromoteRequest req,
            @AuthenticationPrincipal User user) {
        // Proteção básica
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            // Chama o seu SheetService atualizado
            // Chama o serviço (que incrementa no Prelim e copia para o Final)
            sheetService.copyRowToFinal(req.sourcePath(), req.sourceRow(), req.targetPath(), user.getUsername());

            // 1. AVISA O FINAL (Destino)
            // Quem estiver olhando o Final vai ver a linha aparecer/atualizar
            SheetRowInsertedEvent eventoFinal = new SheetRowInsertedEvent(req.targetPath(), req.sourceRow(), user.getUsername());
            sheetEventBroadcaster.sendRowInserted(eventoFinal);

            // 2. AVISA O PRELIM (Origem)  <-- ADICIONE ISTO
            // Quem estiver olhando o Prelim precisa ver o contador subir (ex: de 3 para 4)
            // Ao enviar RowInserted na mesma linha, o frontend recarrega a linha com o valor novo
            SheetRowInsertedEvent eventoPrelim = new SheetRowInsertedEvent(req.sourcePath(), req.sourceRow(), user.getUsername());
            sheetEventBroadcaster.sendRowInserted(eventoPrelim);

            serverLog.warn("SheetControoler", "[copy-to-final] Origem: " + req.sourcePath() + " - Destino " + req.targetPath() + " - Na linha: " + req.sourceRow() + " - Usuário: " + user.getUsername());
            return ResponseEntity.ok().build();

        } catch (IllegalStateException e) {
            // 🛑 AQUI É O PULO DO GATO:
            // Captura o erro "Linha bloqueada por fulano" e manda como 409 Conflict
            serverLog.error("SheetController", "[copy-to-row] Erro ao enviar linha - >", e);
            return ResponseEntity.status(409).body(e.getMessage());

        } catch (IllegalArgumentException e) {
            serverLog.error("SheetController", "[copy-to-row] Erro ao enviar linha - >", e);
            return ResponseEntity.badRequest().body("Linha não encontrada ou inválida.");

        } catch (Exception e) {
            serverLog.error("SheetController", "[copy-to-row] Erro ao enviar linha - >", e);
            return ResponseEntity.internalServerError().body("Erro interno: " + e.getMessage());
        }
    }

    @PostMapping("/notify-restore")
    public ResponseEntity<Void> notifyRestore(@RequestBody java.util.Map<String, String> payload) {
        String path = payload.get("path"); // Ex: "/BDBR/Prelim.csv"
        String user = payload.get("user");

        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // --- [NOVO] Limpeza automática das Laudas ---
        // Chama o serviço para apagar a pasta 'laudas'
        sheetService.clearLaudas(path);

        // --------------------------------------------
        // Dispara o evento para todos os clientes conectados
        sheetEventBroadcaster.sendSheetRestored(path, user);

        serverLog.warn("SheetControoler", "[notify-restory] Origem: " + path + " - Restaurada/Criada pelo Usuário: " + user);
        return ResponseEntity.ok().build();
    }
}
