/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.mirrorpage.server.tabel;

import com.app.mirrorpage.api.dto.StopwatchEvent;
import com.app.mirrorpage.fs.PathResolver;
import com.app.mirrorpage.server.service.FileLockService;
import com.app.mirrorpage.server.service.ServerLog;
import com.app.mirrorpage.server.service.SheetEventBroadcaster;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class SheetService {

    private final PathResolver pathResolver;
    private final SheetEventBroadcaster broadcaster;
    private final CellLockService cellLockService;
    private final FileLockService fileLockService;
    private final ServerLog serverLog;

    // 👇 ADICIONE ESTE MAPA PARA GUARDAR O ESTADO EM MEMÓRIA
    private final Map<String, StopwatchEvent> stopwatchStates = new ConcurrentHashMap<>();

    public SheetService(PathResolver pathResolver,
            SheetEventBroadcaster broadcaster,
            CellLockService cellLockService,
            ServerLog serverLog,
            FileLockService fileLockService) {
        this.pathResolver = pathResolver;
        this.broadcaster = broadcaster;
        this.cellLockService = cellLockService;
        this.serverLog = serverLog;
        this.fileLockService = fileLockService;
    }

    public String loadSheet(String relPath) throws IOException {
        Path file = resolveSheet(relPath);
        if (!Files.exists(file)) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

// Adicionado 'synchronized' para evitar conflito entre usuários
    public synchronized void insertRow(String relPath, int afterRow, String username) throws IOException {
        Path file = resolveSheet(relPath);

        List<String> linhas = Files.exists(file)
                ? Files.readAllLines(file, StandardCharsets.UTF_8)
                : new ArrayList<>();

        if (linhas.isEmpty()) {
            return;
        }

        int fixedDataIndex = 1;

        // afterRow vem do cliente. +2 para pular Header e cair depois da selecionada.
        int novaLinhaIndex = afterRow + 2;

        // Proteções de índice
        if (novaLinhaIndex >= linhas.size()) {
            novaLinhaIndex = linhas.size() - 1;
        }
        if (novaLinhaIndex <= fixedDataIndex) {
            novaLinhaIndex = fixedDataIndex + 1;
        }

        // --- LÓGICA DE LAUDAS ---
        // 1. Descobre a pasta das laudas
        Path laudaDir = resolveLaudaDir(relPath);

        // 2. Calcula o intervalo que precisa mover (da nova linha até o fim dos dados)
        int lastContentIndex = linhas.size() - 2;

        // 3. Empurra os arquivos para baixo (+1) para abrir espaço
        // Ex: Se inseriu na 5, o arquivo 5.txt vira 6.txt, o 6.txt vira 7.txt...
        shiftLaudaFiles(laudaDir, novaLinhaIndex, lastContentIndex, 1);

        // --- LÓGICA DO CSV ---
        String header = linhas.get(0);
        int numCols = header.split(";", -1).length;
        String novaLinha = criarLinhaVazia(numCols);

        // Insere na lista
        linhas.add(novaLinhaIndex, novaLinha);

        int startModelRow = afterRow + 1;

        // Ajusta Locks e Numeração
        cellLockService.shiftLocks(relPath, startModelRow, +1);
        renumerarPaginas(linhas, fixedDataIndex + 1, linhas.size() - 2);

        // Salva e Notifica
        Files.write(file, linhas, StandardCharsets.UTF_8);
        broadcaster.sendRowInserted(new SheetRowInsertedEvent(relPath, afterRow, username));
    }

    public void moveRow(String path, int from, int to, String username) throws Exception {
        Path abs = resolveSheet(path);
        List<String> linhas = Files.readAllLines(abs, StandardCharsets.UTF_8);

        if (linhas.size() < 4) {
            return; // Mínimo: Header, Fixa, 1 Dado, Rodapé
        }

        // --- 1. VALIDAÇÃO DE INTERVALO (A Correção Crítica) ---
        // Verifica se existe algum lock na Origem, no Destino, OU em qualquer linha entre eles.
        // Isso impede que o movimento desalinhe a edição de outro usuário.
        int start = Math.min(from, to);
        int end = Math.max(from, to);

        for (int i = start; i <= end; i++) {
            try {
                // Se encontrar um lock de OUTRA pessoa, estoura erro e cancela tudo.
                validarLinhaLivre(path, i, username, linhas);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("Movimento bloqueado: A linha " + (i + 1)
                        + " está em uso no momento. Aguarde a edição terminar.");
            }
        }

        validarLaudasLivresNoIntervalo(path, start, end);

        int headerIndex = 0;
        int fixedDataIndex = 1;
        int footerIndex = linhas.size() - 1;

        // Cálculo dos índices reais no arquivo físico
        int realFrom = headerIndex + 1 + from;
        int realTo = headerIndex + 1 + to;

        // --- PROTEÇÕES ---
        if (realFrom <= fixedDataIndex || realTo <= fixedDataIndex) {
            return;
        }
        if (realFrom >= footerIndex || realTo >= footerIndex) {
            return;
        }
        if (realFrom == realTo) {
            return;
        }

        Path laudaDir = resolveLaudaDir(path);
        if (laudaDir != null) {
            moveLaudaFile(laudaDir, realFrom, realTo);
        }

        // --- MOVIMENTO EXATO ---
        List<String> mut = new ArrayList<>(linhas);

        // 1. Remove da posição antiga
        String linhaMovida = mut.remove(realFrom);

        // 2. Insere na posição de destino
        mut.add(realTo, linhaMovida);

        // --- RENUMERAÇÃO ---
        renumerarPaginas(mut, fixedDataIndex + 1, footerIndex - 1);

        // --- ATENÇÃO AOS LOCKS ---
        // Removemos a chamada 'shiftLocks' aqui.
        // Motivo: Como validamos acima que NÃO HÁ locks no intervalo afetado, 
        // não precisamos deslocar locks de ninguém. 
        // Se o próprio usuário que moveu tinha locks, eles seriam invalidados ou 
        // liberados pelo front ao soltar o mouse. É mais seguro não mexer no mapa de locks aqui.
        // Salva e notifica
        Files.write(abs, mut, StandardCharsets.UTF_8);
        broadcaster.sendRowMoved(new RowMoveEvent(path, from, to, username));
    }

    private void validarLaudasLivresNoIntervalo(String csvPath, int startModelRow, int endModelRow) {
        for (int r = startModelRow; r <= endModelRow; r++) {

            String laudaRelPath = calcularCaminhoLauda(csvPath, r); // laudas/_BDBR_Prelim/4.txt

            String owner = fileLockService.getOwner(laudaRelPath); // <- adapte conforme seu serviço

            if (owner != null && !owner.isBlank()) {
                // bloqueia independente do dono
                throw new IllegalStateException(
                        "Movimento bloqueado: a lauda da linha " + (r + 1) + " está aberta/editando por: " + owner
                );
            }
        }
    }

    private void moveLaudaFile(Path dir, int fromIndex, int toIndex) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        if (fromIndex == toIndex) {
            return;
        }

        // Regra de Nome: (Index - 1).txt
        // Ex: Linha 2 do CSV é o arquivo "1.txt"
        String fromName = (fromIndex - 1) + ".txt";
        String toName = (toIndex - 1) + ".txt";

        Path sourceFile = dir.resolve(fromName);

        // Nome temporário para guardar o arquivo que está "viajando"
        Path tempFile = dir.resolve("move_temp_" + System.currentTimeMillis() + ".tmp");

        boolean sourceExists = Files.exists(sourceFile);

        try {
            // 1. Tira o arquivo de origem do caminho e guarda no Temp
            if (sourceExists) {
                Files.move(sourceFile, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // 2. Desloca os arquivos intermediários
            if (fromIndex < toIndex) {
                // Movendo para BAIXO (ex: de 2 para 5)
                // O arquivo da 3 vira 2, 4 vira 3, 5 vira 4...
                for (int i = fromIndex + 1; i <= toIndex; i++) {
                    int currentIndex = i;
                    int newIndex = i - 1;

                    Path s = dir.resolve((currentIndex - 1) + ".txt");
                    Path t = dir.resolve((newIndex - 1) + ".txt");

                    if (Files.exists(s)) {
                        Files.move(s, t, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.deleteIfExists(t);
                    }
                }
            } else {
                // Movendo para CIMA (ex: de 5 para 2)
                // O arquivo da 4 vira 5, 3 vira 4, 2 vira 3...
                // Iteramos de trás para frente
                for (int i = fromIndex - 1; i >= toIndex; i--) {
                    int currentIndex = i;
                    int newIndex = i + 1;

                    Path s = dir.resolve((currentIndex - 1) + ".txt");
                    Path t = dir.resolve((newIndex - 1) + ".txt");

                    if (Files.exists(s)) {
                        Files.move(s, t, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.deleteIfExists(t);
                    }
                }
            }

            // 3. Coloca o arquivo original (que estava no Temp) no destino final
            Path targetFile = dir.resolve(toName);
            if (sourceExists) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // Se a linha de origem não tinha lauda, garante que o destino fique vazio
                Files.deleteIfExists(targetFile);
            }

        } catch (IOException e) {
            serverLog.error("SheetService", "[LAUDA] - Erro ao mover lauda", e);
        }
    }

    /**
     * Método auxiliar para garantir a sequência 1, 2, 3... na primeira coluna.
     */
    private void renumerarPaginas(List<String> linhas, int startRow, int endRow) {
        int numeroPagina = 1; // Contador sequencial
        String sep = ";";     // Seu separador CSV

        for (int i = startRow; i <= endRow; i++) {
            String linha = linhas.get(i);

            // Divide a linha preservando colunas vazias (-1)
            String[] cols = linha.split(java.util.regex.Pattern.quote(sep), -1);

            // Se a linha for válida, força a Coluna 0 a ser o número sequencial
            if (cols.length > 0) {
                String novoNum = String.valueOf(numeroPagina);

                // Só altera e recria a string se o número estiver errado
                if (!cols[0].equals(novoNum)) {
                    cols[0] = novoNum;
                    linhas.set(i, String.join(sep, cols));
                }
            }
            numeroPagina++;
        }
    }

    public void deleteRow(String path, int modelRow, String username) throws Exception {
        Path abs = resolveSheet(path);
        List<String> linhas = Files.readAllLines(abs, StandardCharsets.UTF_8);

        // Estrutura Mínima: Header(0) + Fixa(1) + Rodapé(Size-1)
        if (linhas.size() < 3) {
            return;
        }

        validarLinhaLivre(path, modelRow, username, linhas);

        // --- 1. VALIDAR ÍNDICES ---
        int headerIndex = 0;
        int fixedDataIndex = 1;      // Linha Fixa (Model Row 0)
        int footerIndex = linhas.size() - 1;

        // Converte ModelRow (da tabela) para FileRow (do arquivo)
        // Model 0 = File 1
        // Model 1 = File 2
        int fileIndex = headerIndex + 1 + modelRow;

        // Proteção: Não deletar Linha Fixa
        if (fileIndex <= fixedDataIndex) {
            throw new IllegalArgumentException("Não é permitido excluir a linha fixa de topo.");
        }

        // Proteção: Não deletar Rodapé ou fora dos limites
        if (fileIndex >= footerIndex) {
            throw new IllegalArgumentException("Não é permitido excluir o rodapé.");
        }

        Path laudaDir = resolveLaudaDir(path);

        // A. Apaga o arquivo da linha atual
        // Lembra da regra: Arquivo = Index - 1 (Ex: Linha 2 do CSV é o arquivo 1.txt)
        // Se sua lógica mudou, ajuste aqui. Vou manter (index - 1) + ".txt"
        String nomeArquivoParaDeletar = (fileIndex - 1) + ".txt";
        Files.deleteIfExists(laudaDir.resolve(nomeArquivoParaDeletar));
        serverLog.warn("SheetService", "[LAUDA] deletada: " + nomeArquivoParaDeletar);

        // B. Puxa os arquivos subsequentes para CIMA (delta = -1)
        // Intervalo: da linha seguinte (fileIndex + 1) até a última linha de dados
        int lastContentIndex = linhas.size() - 2;
        shiftLaudaFiles(laudaDir, fileIndex + 1, lastContentIndex, -1);

        // --- 3. EXECUÇÃO ---
        List<String> mut = new ArrayList<>(linhas);

        // Remove a linha
        mut.remove(fileIndex);

        // O arquivo diminuiu de tamanho, então o rodapé agora é um índice menor
        int novoFooterIndex = mut.size() - 1;

        // --- 4. RENUMERAR ---
        // Renumera da primeira linha móvel (2) até antes do rodapé
        // Importante: Como removemos uma linha, os números de baixo precisam subir.
        // O método renumerarPaginas vai sobrescrever a coluna 0 sequencialmente (1, 2, 3...)
        renumerarPaginas(mut, fixedDataIndex + 1, novoFooterIndex - 1);

        cellLockService.shiftLocks(path, modelRow, -1);

        // --- 5. SALVAR E NOTIFICAR ---
        Files.write(abs, mut, StandardCharsets.UTF_8);

        // Avisa que a linha 'modelRow' foi deletada
        broadcaster.sendRowDeleted(new RowDeletedEvent(path, modelRow, username));
    }

    public List<String> listarPastasRaiz() {
        // Resolve o caminho
        Path dirProdutos = pathResolver.resolveSafe("");

        if (Files.exists(dirProdutos)) {
            try {
                Files.list(dirProdutos).forEach(p -> p.getFileName());
            } catch (IOException e) {

            }
        }

        if (!Files.exists(dirProdutos) || !Files.isDirectory(dirProdutos)) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.list(dirProdutos)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /*──────── Helpers ────────*/
    private Path resolveSheet(String relPath) {
        // adapta para o seu PathResolver real
        return pathResolver.resolveSafe(relPath);
    }

    // Certifique-se de que este método auxiliar está EXATAMENTE assim:
    private void validarLinhaLivre(String path, int modelRow, String username, List<String> linhas) {
        if (linhas.isEmpty()) {
            return;
        }

        // Descobre quantas colunas existem lendo o cabeçalho
        String header = linhas.get(0);
        int numCols = header.split(";", -1).length;

        // Varre TODAS as colunas dessa linha
        for (int col = 0; col < numCols; col++) {
            // Pergunta ao LockService quem é o dono
            String owner = cellLockService.getOwner(path, modelRow, col);

            // Se tem dono e NÃO sou eu, BLOQUEIA!
            if (owner != null && !owner.equals(username)) {
                // Esta mensagem é a que vai aparecer no seu JOptionPane
                throw new IllegalStateException("Linha bloqueada. Coluna " + (col + 1) + " em edição por: " + owner);
            }
        }
    }

    public synchronized void copyRowToFinal(String sourcePath, int sourceRow, String targetPath, String user) throws IOException {

        // --- 1. PREPARAÇÃO DA ORIGEM (PRELIM) ---
        Path srcCsv = pathResolver.resolveSafe(sourcePath);
        if (!Files.exists(srcCsv)) {
            throw new FileNotFoundException("Prelim não encontrado");
        }

        List<String> srcLines = Files.readAllLines(srcCsv, StandardCharsets.UTF_8);

        if (srcLines.isEmpty()) {
            throw new IllegalArgumentException("Arquivo de origem vazio.");
        }

        // Índice da linha no arquivo (0 = header)
        int srcFileIndex = sourceRow + 1;

        // 🔹 Aqui permitimos a ÚLTIMA linha (encerramento) também
        if (srcFileIndex >= srcLines.size()) {
            throw new IllegalArgumentException("A linha de origem não existe mais.");
        }

        // Descobre se a linha é a ÚLTIMA (encerramento)
        boolean isEncerramento = (srcFileIndex == srcLines.size() - 1);

        // Se quiser, você pode pular validação de lock para encerramento
        // mas mantive igual para todos:
        validarLinhaLivre(sourcePath, sourceRow, user, srcLines);

        // --- LÓGICA DO CONTADOR (INCREMENTA COLUNA 1) ---
        String lineContent = srcLines.get(srcFileIndex);
        String[] columns = lineContent.split(";", -1);
        int numCols = srcLines.get(0).split(";", -1).length; // Total colunas pelo header

        // Expande array se necessário
        if (columns.length < numCols) {
            String[] newCols = new String[numCols];
            System.arraycopy(columns, 0, newCols, 0, columns.length);
            for (int i = 0; i < numCols; i++) {
                if (newCols[i] == null) {
                    newCols[i] = "";
                }
            }
            columns = newCols;
        }

        int contador = 0;
        try {
            if (!columns[1].trim().isEmpty()) {
                contador = Integer.parseInt(columns[1].trim());
            }
        } catch (Exception e) {
            contador = 0;
        }
        contador++;
        columns[1] = String.valueOf(contador);

        String lineContentUpdated = String.join(";", columns);

        // Atualiza a linha na ORIGEM (Prelim)
        srcLines.set(srcFileIndex, lineContentUpdated);
        Files.write(srcCsv, srcLines, StandardCharsets.UTF_8);

        // --- 2. TRATAMENTO DO DESTINO (FINAL) ---
        Path tgtCsv = pathResolver.resolveSafe(targetPath);

        // Se não existir, cria Header + Rodapé inicial
        if (!Files.exists(tgtCsv)) {
            Files.createFile(tgtCsv);
            String header = srcLines.get(0);
            String footer = criarLinhaVazia(numCols).replaceFirst("^0", "");
            Files.writeString(tgtCsv, header + "\n" + footer);
        }

        List<String> tgtLines = Files.readAllLines(tgtCsv, StandardCharsets.UTF_8);

        if (tgtLines.isEmpty()) {
            // Garante que tenha pelo menos header + rodapé
            String header = srcLines.get(0);
            String footer = criarLinhaVazia(numCols).replaceFirst("^0", "");
            tgtLines.clear();
            tgtLines.add(header);
            tgtLines.add(footer);
        }

        // 🟢 PASSO A: REMOVER O RODAPÉ (Última linha atual)
        String fixedFooterRow;
        if (tgtLines.size() > 1) {
            int lastIndex = tgtLines.size() - 1;
            fixedFooterRow = tgtLines.remove(lastIndex);
        } else {
            fixedFooterRow = criarLinhaVazia(numCols).replaceFirst("^0", "");
        }

        // 🔸 CASO ESPECIAL: SE A LINHA DE ORIGEM FOR ENCERRAMENTO
        if (isEncerramento) {
            // Aqui você decidiu que essa linha deve virar o NOVO rodapé do FINAL.
            fixedFooterRow = lineContentUpdated;

            // Opcional: renumerar páginas dos dados existentes, se fizer sentido
            if (tgtLines.size() > 2) {
                renumerarPaginas(tgtLines, 2, tgtLines.size() - 1);
            }

            // Devolve o novo rodapé
            tgtLines.add(fixedFooterRow);
            Files.write(tgtCsv, tgtLines, StandardCharsets.UTF_8);

            // CÓPIA DA LAUDA (se quiser que o encerramento também tenha lauda associada)
            Path srcLaudaDir = resolveLaudaDir(sourcePath);
            Path tgtLaudaDir = resolveLaudaDir(targetPath);
            if (!Files.exists(tgtLaudaDir)) {
                Files.createDirectories(tgtLaudaDir);
            }

            Path srcTxt = srcLaudaDir.resolve(sourceRow + ".txt");
            Path tgtTxt = tgtLaudaDir.resolve(sourceRow + ".txt");

            if (Files.exists(srcTxt)) {
                Files.copy(srcTxt, tgtTxt, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(tgtTxt);
            }
            return;
        }

        // 🔹 CASO NORMAL: NÃO É ENCERRAMENTO → COPIA COMO LINHA DE DADO
        // 🟢 PASSO B: PREENCHER VAZIOS (PADDING)
        int targetListIndex = sourceRow + 1;

        while (tgtLines.size() <= targetListIndex) {
            tgtLines.add(criarLinhaVazia(numCols));
        }

        // 🟢 PASSO C: VERIFICAR LOCK E INSERIR
        if (tgtLines.size() > targetListIndex) {
            validarLinhaLivre(targetPath, sourceRow, user, tgtLines);
        }

        // Sobrescreve a linha alvo com os dados novos
        tgtLines.set(targetListIndex, lineContentUpdated);

        // Se for a última linha de dados, adiciona uma linha vazia extra
        if (targetListIndex == tgtLines.size() - 1) {
            tgtLines.add(criarLinhaVazia(numCols));
        }

        // Renumera páginas (Da linha 2 até o fim dos dados atuais)
        if (tgtLines.size() > 2) {
            renumerarPaginas(tgtLines, 2, tgtLines.size() - 1);
        }

        // 🟢 PASSO D: DEVOLVER O RODAPÉ
        tgtLines.add(fixedFooterRow);

        // Salva o Final
        Files.write(tgtCsv, tgtLines, StandardCharsets.UTF_8);

        // --- 3. CÓPIA DA LAUDA ---
        Path srcLaudaDir = resolveLaudaDir(sourcePath);
        Path tgtLaudaDir = resolveLaudaDir(targetPath);
        if (!Files.exists(tgtLaudaDir)) {
            Files.createDirectories(tgtLaudaDir);
        }

        Path srcTxt = srcLaudaDir.resolve(sourceRow + ".txt");
        Path tgtTxt = tgtLaudaDir.resolve(sourceRow + ".txt");

        if (Files.exists(srcTxt)) {
            Files.copy(srcTxt, tgtTxt, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(tgtTxt);
        }

    }

    // 👇 ADICIONE ESTE MÉTODO PRIVADO NA SUA CLASSE SheetService
    // Ele converte "/BDBR/Prelim.csv" em um Path para ".../laudas/_BDBR_Prelim"
    private Path resolveLaudaDir(String csvRelPath) {
        // 1. Remove a extensão .csv
        String cleanName = csvRelPath.replace(".csv", "");

        // 2. Troca as barras / ou \ por _ (para criar um nome de pasta plano)
        // Ex: "BDBR/Prelim" vira "BDBR_Prelim"
        String folderName = cleanName.replaceAll("[\\\\/]", "_");

        // 3. Garante que começa com _ (padrão que vimos nos seus logs)
        if (!folderName.startsWith("_")) {
            folderName = "_" + folderName;
        }

        // 4. Usa o pathResolver para pegar o caminho completo dentro da pasta "laudas"
        return pathResolver.resolveSafe("laudas/" + folderName);
    }

// -------------------------------------------------------------------------
    // MÉTODOS AUXILIARES PARA MANIPULAÇÃO DE LAUDAS (.txt)
    // -------------------------------------------------------------------------
    /**
     * Move os arquivos de texto em massa para acompanhar a inserção/exclusão.
     */
    private void shiftLaudaFiles(Path dir, int startIndex, int endIndex, int delta) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }

        // Para INSERÇÃO (delta > 0): Movemos de trás para frente (Ex: 10->11, depois 9->10...)
        // Isso evita sobrescrever um arquivo que ainda não foi movido.
        if (delta > 0) {
            for (int i = endIndex; i >= startIndex; i--) {
                moveFileIndex(dir, i, i + delta);
            }
        } else if (delta < 0) {
            for (int i = startIndex; i <= endIndex; i++) {
                moveFileIndex(dir, i, i + delta);
            }
        }
        // Para REMOÇÃO (delta < 0): Moveríamos da frente para trás (implementar se precisar no deleteRow)
    }

    /**
     * Renomeia um arquivo específico baseando-se no índice da linha. Ex:
     * Renomeia "5.txt" para "6.txt"
     */
    private void moveFileIndex(Path dir, int oldIndex, int newIndex) {
        // Assume que o nome do arquivo é apenas o índice da linha (ex: 5.txt)
        // Se sua lógica for (index-1), ajuste aqui.
        String oldName = (oldIndex - 1) + ".txt";
        String newName = (newIndex - 1) + ".txt";

        Path source = dir.resolve(oldName);
        Path target = dir.resolve(newName);

        if (Files.exists(source)) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                serverLog.warn("SheetService", "[LAUDA] Movida da linha: " + oldName + " para linha: " + newName);
            } catch (IOException e) {
                serverLog.error("SheetService", "[LAUDA] Erro ao mover", e);
            }
        }
    }

    private String calcularCaminhoLauda(String csvPath, int row) {
        String nomeLimpo = csvPath.replace(".csv", "").replaceAll("[\\\\/]", "_");
        if (!nomeLimpo.startsWith("_")) {
            nomeLimpo = "_" + nomeLimpo;
        }
        return "laudas/" + nomeLimpo + "/" + row + ".txt";
    }

    // Método auxiliar para criar a linha vazia no CSV
    private String criarLinhaVazia(int numCols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numCols; i++) {
            if (i > 0) {
                sb.append(';');
            }
            if (i == 0) {
                sb.append("0"); // Num Pag provisório
            } else if (i == 8 || i == 9 || i == 10) {
                sb.append("00:00");
            } else if (i == 13) {
                sb.append("00:00:00");
            } else {
                sb.append("");
            }
        }
        return sb.toString();
    }

    /**
     * Limpa as laudas associadas a um arquivo CSV específico.
     *
     * @param csvPath Caminho do CSV (ex: "/BDBR/Prelim.csv")
     */
    public void clearLaudas(String csvPath) {
        try {
            // 1. Usa o resolveLaudaDir para obter a pasta correta (ex: .../laudas/_BDBR_Prelim)
            Path laudasDir = resolveLaudaDir(csvPath);

            if (Files.exists(laudasDir) && Files.isDirectory(laudasDir)) {
                // 2. Lista e deleta os arquivos .txt
                try (Stream<Path> files = Files.list(laudasDir)) {
                    files.forEach(file -> {
                        try {
                            if (Files.isRegularFile(file) && file.toString().endsWith(".txt")) {
                                Files.delete(file);
                                serverLog.warn("SheetService", "[LAUDA] Deletada: " + file.getFileName());
                            }
                        } catch (IOException e) {
                            serverLog.error("SheetService", "[LAUDA] Falha ao deletar: " + file, e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            serverLog.error("SheetService", "[LAUDA] Erro ao limpar laudas ", e);

        }
    }

    public StopwatchEvent getStopwatchState(String path) {
        StopwatchEvent stored = stopwatchStates.get(path);

        if (stored == null) {
            return null;
        }

        // Se o cronômetro está RODANDO (START), precisamos somar o tempo que passou desde o start até agora.
        if ("START".equals(stored.getAction())) {
            long now = System.currentTimeMillis();
            long delta = now - stored.getTimestamp(); // Tempo decorrido no servidor

            // Retorna um novo evento simulando que o cronômetro começou AGORA, 
            // mas com o tempo acumulado ajustado.
            return new StopwatchEvent(
                    stored.getAction(),
                    stored.getUser(),
                    stored.getPath(),
                    stored.getAccumulatedTime() + delta, // Soma o tempo que passou
                    stored.isSync(),
                    now // Timestamp atualizado
            );
        }

        // Se está PAUSADO ou ZERADO, retorna como está
        return stored;
    }

    public void handleStopwatchEvent(StopwatchEvent ev) {
// 1. Força o timestamp do servidor para garantir consistência entre todos os clientes
        ev.setTimestamp(System.currentTimeMillis());

        // 2. Atualiza memória
        if ("RESET".equals(ev.getAction()) || "NON_SYNC".equals(ev.getAction())) {
            stopwatchStates.remove(ev.getPath());
        } else {
            // Salva START ou PAUSE com o timestamp do servidor
            stopwatchStates.put(ev.getPath(), ev);
        }

        // 3. Broadcast (via WebSocket)
        broadcaster.sendStopwatchEvent(ev);
    }

}
