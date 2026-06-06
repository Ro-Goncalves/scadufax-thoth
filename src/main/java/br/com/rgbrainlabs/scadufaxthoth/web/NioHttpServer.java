package br.com.rgbrainlabs.scadufaxthoth.web;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.search.VectorSearcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Servidor HTTP/1.1 mínimo: reactor single-threaded em <b>busy-poll</b> sobre
 * canais NIO não-bloqueantes, <b>sem {@link java.nio.channels.Selector}</b>.
 *
 * <p>Por que sem Selector? No GraalVM Native Image o {@code Selector} (epoll)
 * entra em busy-spin sem reportar eventos nem servir — defeito que derrubou tanto
 * o Jetty quanto uma primeira versão deste reactor. A varredura direta das
 * conexões com {@code read()}/{@code accept()} não-bloqueantes não usa epoll e
 * funciona no binário nativo. É o padrão dos top performers da Rinha
 * (lucasmontano: busy-poll, 0,25ms p99; arthurd3): um único thread fazendo
 * accept → read → search → write inline, sem pool de threads e sem context-switch.
 *
 * <p><b>Backoff CFS-aware (crítico sob cota fracionária).</b> O loop cede a CPU
 * ({@code parkNanos}) sempre que um ciclo inteiro não encontra I/O pronto —
 * inclusive com conexões keep-alive vivas mas ociosas. Sem isso, sob {@code cpus<1.0}
 * (cota de CFS), o spin puro queima a cota girando e o kernel estrangula a instância,
 * jogando a cauda de p95/p99 para ~55ms. O backoff mantém o uso abaixo da cota e a
 * cauda baixa. Em core dedicado SEM cota o spin puro seria ótimo; sob cota fracionária
 * (cenário da Rinha/0,45 vCPU) o backoff é obrigatório. Ver detalhes em {@code run()}.
 *
 * <p>Suporta keep-alive e pipelining de HTTP/1.1. Sem alocação no hot path: o
 * vetor de query, o buffer de corpo e as respostas HTTP são reaproveitados.
 */
public final class NioHttpServer implements AutoCloseable {

    private static final int  BACKLOG     = 1024;
    private static final int  READ_CHUNK  = 8192;
    private static final int  MAX_REQUEST = 64 * 1024;        // teto defensivo por requisição
    private static final long IDLE_PARK_NS = 50_000;          // backoff (50µs) quando um ciclo não acha trabalho

    private static final byte[] CRLFCRLF      = {'\r', '\n', '\r', '\n'};
    private static final byte[] H_CONTENT_LEN = "content-length:".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] PATH_FRAUD = "/fraud-score".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PATH_READY = "/ready".getBytes(StandardCharsets.US_ASCII);

    private final int port;
    private final VectorSearcher searcher;
    private final FraudRequestParser parser;
    private final int k;

    // ── Estado reaproveitado (single-threaded → sem ThreadLocal) ─────────────
    private final float[] queryVec = new float[14];
    private final byte[]  reqBody  = new byte[MAX_REQUEST];

    // ── Respostas HTTP pré-montadas (zero serialização no hot path) ──────────
    private final byte[][] httpFraud; // índice = fraud_count ∈ [0, k]
    private final byte[]   httpReady;
    private final byte[]   httpNotFound;
    private final byte[]   httpBadRequest;

    private ServerSocketChannel serverChannel;
    private volatile boolean running;

    public NioHttpServer(int port, VectorSearcher searcher, FraudRequestParser parser,
                         PreSerializedResponseTable responses) {
        this.port     = port;
        this.searcher = searcher;
        this.parser   = parser;
        this.k        = responses.k();

        this.httpFraud = new byte[k + 1][];
        for (int i = 0; i <= k; i++) {
            this.httpFraud[i] = buildHttpResponse(200, "application/json", responses.get(i));
        }
        this.httpReady      = buildHttpResponse(200, "text/plain", "OK".getBytes(StandardCharsets.UTF_8));
        this.httpNotFound   = buildHttpResponse(404, "text/plain", new byte[0]);
        this.httpBadRequest = buildHttpResponse(400, "text/plain", new byte[0]);
    }

    /** Sobe o socket e roda o loop de busy-poll na thread chamadora (bloqueia). */
    public void run() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(port), BACKLOG);
        running = true;

        List<Conn> conns = new ArrayList<>();

        while (running) {
            boolean didWork = false;

            // 1) aceita novas conexões (não-bloqueante)
            SocketChannel ch;
            while ((ch = serverChannel.accept()) != null) {
                ch.configureBlocking(false);
                ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                conns.add(new Conn(ch));
                didWork = true;
            }

            // 2) serve cada conexão (read/process/write não-bloqueantes)
            for (int i = 0; i < conns.size(); ) {
                Conn c = conns.get(i);
                boolean alive = true;
                try {
                    if (c.pendingWrite != null) {
                        if (flush(c)) didWork = true;
                    } else {
                        int n = serve(c);
                        if (n < 0)      alive = false;
                        else if (n > 0) didWork = true;
                    }
                } catch (IOException e) {
                    alive = false;
                }
                if (!alive) {
                    closeConn(c);
                    int last = conns.size() - 1;
                    conns.set(i, conns.get(last));
                    conns.remove(last);
                } else {
                    i++;
                }
            }

            // Backoff sempre que um ciclo inteiro não encontrou trabalho — INCLUSIVE
            // com conexões keep-alive vivas mas ociosas. Sob cota de CFS fracionária
            // (cpus=0.45 no compose), spin puro varre O(N) conexões e queima a cota
            // inteira girando (~45ms/100ms); o kernel então estrangula a instância
            // pelos ~55ms restantes do período e a cauda de p99/p95 dispara para ~55ms.
            // Ceder a CPU (park) quando não há I/O pronto mantém o uso abaixo da cota
            // e elimina o throttle — os ~50µs de park são desprezíveis vs. ~55ms de
            // estrangulamento. Em core dedicado SEM cota (cenário lucasmontano) spin
            // puro seria ótimo; sob cota fracionária ele é o pior caso. Ver Issue 08.
            if (!didWork) LockSupport.parkNanos(IDLE_PARK_NS);
        }
    }

    // ── Read + dispatch de uma conexão ───────────────────────────────────────────

    /** Lê o que houver e processa requisições completas. Retorna bytes lidos, ou -1 em EOF. */
    private int serve(Conn c) throws IOException {
        c.ensureCapacity(READ_CHUNK);
        ByteBuffer bb = ByteBuffer.wrap(c.buf, c.len, c.buf.length - c.len);
        int n = c.ch.read(bb);
        if (n == -1) return -1;
        if (n == 0)  return 0;
        c.len += n;
        process(c);
        return n;
    }

    /** Processa todas as requisições completas já presentes no buffer (pipelining). */
    private void process(Conn c) throws IOException {
        while (true) {
            int headerEnd = indexOf(c.buf, c.len, CRLFCRLF);
            if (headerEnd < 0) {
                if (c.len > MAX_REQUEST) throw new IOException("header grande demais");
                return; // precisa de mais bytes
            }
            int bodyStart = headerEnd + 4;
            int contentLength = parseContentLength(c.buf, headerEnd);
            int total = bodyStart + contentLength;
            if (total > MAX_REQUEST) throw new IOException("requisição grande demais");
            if (c.len < total) return; // corpo incompleto

            byte[] resp = dispatch(c.buf, bodyStart, contentLength);

            int remaining = c.len - total;
            if (remaining > 0) System.arraycopy(c.buf, total, c.buf, 0, remaining);
            c.len = remaining;

            write(c, resp);
            if (c.pendingWrite != null) return; // write não completou; retoma no busy-poll
            if (remaining == 0) return;
        }
    }

    private byte[] dispatch(byte[] buf, int bodyStart, int contentLength) {
        if (matchMethodPath(buf, 'P', PATH_FRAUD)) {
            if (contentLength <= 0 || contentLength > reqBody.length) return httpBadRequest;
            System.arraycopy(buf, bodyStart, reqBody, 0, contentLength);
            parser.parse(reqBody, contentLength, queryVec);
            List<SearchResult> topK = searcher.search(queryVec, k);
            int fraudCount = 0;
            for (int i = 0; i < topK.size(); i++) {
                if ("fraud".equals(topK.get(i).label())) fraudCount++;
            }
            return httpFraud[fraudCount];
        }
        if (matchMethodPath(buf, 'G', PATH_READY)) {
            return httpReady;
        }
        return httpNotFound;
    }

    // ── Write não-bloqueante (com tratamento de write parcial) ────────────────────

    private void write(Conn c, byte[] resp) throws IOException {
        ByteBuffer out = ByteBuffer.wrap(resp);
        c.ch.write(out);
        if (out.hasRemaining()) {
            c.pendingWrite = out;
        }
    }

    /** Tenta escoar um write pendente. Retorna true se progrediu; ao terminar, retoma o pipeline. */
    private boolean flush(Conn c) throws IOException {
        ByteBuffer out = c.pendingWrite;
        int n = c.ch.write(out);
        if (!out.hasRemaining()) {
            c.pendingWrite = null;
            process(c); // retoma requisições pipelined pendentes
        }
        return n > 0;
    }

    // ── Parsing HTTP mínimo ──────────────────────────────────────────────────────

    /** Confirma método (1ª letra) e path exato na request line. */
    private static boolean matchMethodPath(byte[] buf, char methodFirst, byte[] path) {
        if (buf[0] != methodFirst) return false;
        int i = 0;
        while (i < buf.length && buf[i] != ' ') i++; // fim do método
        i++; // início do path
        for (int p = 0; p < path.length; p++, i++) {
            if (i >= buf.length || buf[i] != path[p]) return false;
        }
        return i < buf.length && (buf[i] == ' ' || buf[i] == '?');
    }

    /** Lê Content-Length (case-insensitive) dentro da região de headers. */
    private static int parseContentLength(byte[] buf, int headerEnd) {
        int i = 0;
        while (i < headerEnd) {
            if (matchesCi(buf, i, H_CONTENT_LEN)) {
                int j = i + H_CONTENT_LEN.length;
                while (j < headerEnd && (buf[j] == ' ' || buf[j] == '\t')) j++;
                int val = 0;
                while (j < headerEnd && buf[j] >= '0' && buf[j] <= '9') {
                    val = val * 10 + (buf[j] - '0');
                    j++;
                }
                return val;
            }
            while (i < headerEnd && buf[i] != '\n') i++;
            i++;
        }
        return 0;
    }

    private static boolean matchesCi(byte[] buf, int off, byte[] lowerKey) {
        if (off + lowerKey.length > buf.length) return false;
        for (int i = 0; i < lowerKey.length; i++) {
            byte b = buf[off + i];
            if (b >= 'A' && b <= 'Z') b += 32; // toLowerCase ASCII
            if (b != lowerKey[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] buf, int len, byte[] needle) {
        int max = len - needle.length;
        outer:
        for (int i = 0; i <= max; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (buf[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ── Construção de respostas ──────────────────────────────────────────────────

    private static byte[] buildHttpResponse(int status, String contentType, byte[] body) {
        String reason = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            default  -> "OK";
        };
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: keep-alive\r\n\r\n";
        byte[] h = head.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[h.length + body.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(body, 0, out, h.length, body.length);
        return out;
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────────────

    private void closeConn(Conn c) {
        try { c.ch.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (serverChannel != null) serverChannel.close();
    }

    // ── Estado por conexão ───────────────────────────────────────────────────────

    private static final class Conn {
        final SocketChannel ch;
        byte[] buf = new byte[READ_CHUNK];
        int len = 0;
        ByteBuffer pendingWrite;

        Conn(SocketChannel ch) { this.ch = ch; }

        void ensureCapacity(int extra) {
            if (len + extra > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
            }
        }
    }
}
