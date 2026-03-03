# Mirror Page 📺

> "A notícia não pode parar. Quando o sistema falha, o Mirror Page assume."

O **Mirror Page** é uma aplicação Java robusta desenvolvida para atuar como um sistema de contingência (backup) e coordenação em redações de telejornalismo. Nascido da necessidade de resiliência durante falhas críticas de sistemas padrão (como o iNews), o software garante a continuidade da operação, permitindo a leitura de laudas e a progressão do espelho de forma ágil, autônoma e ininterrupta.

## 🚀 Funcionalidades Principais

* **Sincronização em Tempo Real (Zero Delay):** Graças à comunicação via Sockets, o carregamento do espelho e as atualizações nas laudas chegam à tela do âncora instantaneamente, sem necessidade de recarregar a página (F5).
* **Cronômetro Nativo de Alta Precisão:** Utiliza *Multithreading* para isolar o relógio da interface gráfica. Isso garante contagem exata para o diretor de TV no momento do corte, mesmo se o sistema estiver processando arquivos pesados em segundo plano.
* **Interface Adaptável (UX):** Alternância fluida entre **Modo Dark** (conforto visual para o estúdio) e **Modo Light** (ideal para a iluminação da redação).
* **Eficiência e Portabilidade:** Otimizado com gerenciamento inteligente de memória (JVM / Lazy Loading) para rodar com leveza até mesmo em hardwares antigos da redação.

## 🛠️ Stack Tecnológica

O sistema é dividido em uma arquitetura desacoplada:

**Backend (Servidor - `mp_server_linux`)**
* **Linguagem:** Java
* **Framework Central:** Spring Boot (Gestão de infraestrutura e ciclo de vida)
* **Comunicação:** Sockets TCP/IP (Conexões Full-Duplex persistentes)
* **SO Alvo:** Linux (Foco em estabilidade)

**Frontend (Cliente)**
* **Linguagem:** Java
* **GUI (Interface Gráfica):** Java Swing (Componentes nativos e renderização via *Event Dispatch Thread*)
* **SO Alvo:** Windows (Estações de trabalho da redação e estúdio)

## 🏗️ Arquitetura



A aplicação utiliza o modelo **Cliente-Servidor**. O backend (`mp_server_linux`) atua como a única fonte da verdade. O Spring Boot sobe a aplicação e abre um serviço de Sockets. Os clientes (aplicação Desktop) se conectam a este túnel. 

Se um terminal do estúdio travar ou for desligado, a informação não se perde: o servidor permanece blindado e o usuário pode simplesmente abrir o cliente em outra máquina e retomar o jornal de onde parou.

## 🔮 Próximos Passos (Roadmap)

A arquitetura do Mirror Page já foi desenhada pensando no futuro. As próximas atualizações incluem:

- [ ] **Mirror Page Mobile / Web:** Criação de uma interface web-based consumindo o mesmo backend, permitindo que repórteres na rua acessem laudas pelo celular.
- [ ] **Expansão de Casos de Uso:** Adaptação do software para a gestão e roteirização de grandes eventos corporativos e shows.

---

## 👥 Autores

Desenvolvido para garantir que o fluxo da informação nunca pare por:
* **Filipe Alves**
