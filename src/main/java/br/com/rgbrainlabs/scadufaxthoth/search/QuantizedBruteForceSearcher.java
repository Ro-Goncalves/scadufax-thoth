package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.List;

/**
 * Buscador força-bruta sobre vetores quantizados (int8 ou int16).
 *
 * Sucessor do MmapBruteForceSearcher para o caminho quantizado. Mantém o
 * mesmo princípio de mmap em memória nativa com Arena compartilhada, mas
 * sobre artefatos de largura fixa produzidos por QuantizedDatasetBuilder.
 *
 * Distâncias são calculadas no espaço inteiro via DistanceCalculator:
 *   int8 : sum((qa - da)²) como int  — máx ≈ 14 × 254² = 903 224, cabe em int
 *   int16: sum((qa - da)²) como long — máx ≈ 14 × 20 000² = 5,6 × 10⁹, cabe em long
 *
 * A ordem de ranking é preservada em relação ao float32 porque a escala é
 * constante por dimensão: dist_int = dist_float × SCALE².
 */
public final class QuantizedBruteForceSearcher implements VectorSearcher, AutoCloseable {

    public enum Dtype { I8, I16 }

    private static final int DIMS      = 14;
    private static final int SCALE_I8  = 127;
    private static final int SCALE_I16 = 10_000;

    private final Dtype dtype;
    private final int count;
    private final BitSet labels;
    private final Arena arena;
    private final boolean ownsArena;
    private final MemorySegment vectors;
    private final DistanceCalculator calculator;

    public QuantizedBruteForceSearcher(Path dataDir, Dtype dtype, DistanceCalculator calculator) throws IOException {
        this(dataDir, dtype, calculator, Arena.ofShared(), true);
    }

    public QuantizedBruteForceSearcher(Path dataDir, Dtype dtype, DistanceCalculator calculator, Arena arena) throws IOException {
        this(dataDir, dtype, calculator, arena, false);
    }

    private QuantizedBruteForceSearcher(Path dataDir, Dtype dtype, DistanceCalculator calculator, Arena arena, boolean ownsArena) throws IOException {
        this.dtype = dtype;
        this.calculator = calculator;
        this.arena = arena;
        this.ownsArena = ownsArena;

        int parsedCount = 0;
        for (String line : Files.readAllLines(dataDir.resolve("meta.properties"))) {
            if (line.startsWith("count=")) {
                parsedCount = Integer.parseInt(line.substring(6).trim());
                break;
            }
        }
        if (parsedCount == 0) {
            throw new IllegalStateException(
                    "meta.properties em '" + dataDir + "' sem count ou count=0. "
                  + "Execute QuantizedDatasetBuilder primeiro.");
        }
        this.count = parsedCount;

        // labels.bin — bitset empacotado
        byte[] lb = Files.readAllBytes(dataDir.resolve("labels.bin"));
        this.labels = new BitSet(count);
        for (int bi = 0; bi < lb.length; bi++) {
            int b = lb[bi] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                int idx = bi * 8 + bit;
                if (idx >= count) break;
                if ((b & (1 << bit)) != 0) labels.set(idx);
            }
        }

        // mmap do arquivo de vetores — channel pode ser fechado após o map
        String fileName = dtype == Dtype.I8 ? "vectors-i8.bin" : "vectors-i16.bin";
        try (FileChannel ch = FileChannel.open(dataDir.resolve(fileName), StandardOpenOption.READ)) {
            this.vectors = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, int k) {
        TopKSelector selector = new TopKSelector(k);
        if (dtype == Dtype.I8) {
            searchI8(queryVector, selector);
        } else {
            searchI16(queryVector, selector);
        }
        return selector.materialize();
    }

    private void searchI8(float[] queryVector, TopKSelector selector) {
        byte[] q = quantizeI8(queryVector);
        for (int i = 0; i < count; i++) {
            double dist = calculator.calculateI8(q, vectors, i * (long) DIMS, DIMS);
            selector.tryInsert(dist, labels.get(i) ? (byte) 1 : (byte) 0);
        }
    }

    private void searchI16(float[] queryVector, TopKSelector selector) {
        short[] q = quantizeI16(queryVector);
        for (int i = 0; i < count; i++) {
            double dist = calculator.calculateI16(q, vectors, i * DIMS * 2L, DIMS);
            selector.tryInsert(dist, labels.get(i) ? (byte) 1 : (byte) 0);
        }
    }

    @Override
    public void close() {
        if (ownsArena) {
            arena.close();
        }
    }

    private static byte[] quantizeI8(float[] v) {
        byte[] q = new byte[DIMS];
        for (int d = 0; d < DIMS; d++) {
            int r = Math.round(v[d] * SCALE_I8);
            if (r < -127) r = -127;
            if (r > 127)  r = 127;
            q[d] = (byte) r;
        }
        return q;
    }

    private static short[] quantizeI16(float[] v) {
        short[] q = new short[DIMS];
        for (int d = 0; d < DIMS; d++) {
            int r = Math.round(v[d] * SCALE_I16);
            if (r < Short.MIN_VALUE) r = Short.MIN_VALUE;
            if (r > Short.MAX_VALUE) r = Short.MAX_VALUE;
            q[d] = (short) r;
        }
        return q;
    }

}

