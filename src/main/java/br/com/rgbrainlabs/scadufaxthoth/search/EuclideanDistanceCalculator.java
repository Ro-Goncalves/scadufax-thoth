package br.com.rgbrainlabs.scadufaxthoth.search;

import java.nio.ByteBuffer;

/**
 * Kernel de distância euclidiana ao quadrado sobre um {@link ByteBuffer} mapeado.
 *
 * <p>Lê via {@code ByteBuffer} absoluto (não {@code MemorySegment}): no GraalVM Native
 * Image os loops sobre ByteBuffer são auto-vetorizados (AVX2), enquanto o acesso FFM
 * via {@code MemorySegment.get(layout, off)} não é otimizado — fonte do gargalo de ~10ms
 * por busca no nativo. Os acessos absolutos ({@code get(int)}) não mutam a posição do
 * buffer, então são seguros no reactor single-thread.
 */
public class EuclideanDistanceCalculator implements DistanceCalculator {

    @Override
    public double calculate(float[] queryVector, ByteBuffer buffer, long offset, int dimensions) {
        // Path float32 (usado por testes). Lê BIG_ENDIAN explicitamente, sem depender da
        // ordem do buffer — não é hot path.
        double squaredDistance = 0.0;
        int p = (int) offset;
        for (int i = 0; i < dimensions; i++) {
            int bits = ((buffer.get(p)     & 0xFF) << 24)
                     | ((buffer.get(p + 1) & 0xFF) << 16)
                     | ((buffer.get(p + 2) & 0xFF) << 8)
                     |  (buffer.get(p + 3) & 0xFF);
            float vectorVal = Float.intBitsToFloat(bits);
            float diff = queryVector[i] - vectorVal;
            squaredDistance += (diff * diff);
            p += 4;
        }
        return squaredDistance;
    }

    @Override
    public double calculateI8(byte[] query, ByteBuffer buffer, long base, int dims) {
        int b = (int) base;
        int dist = 0;
        for (int d = 0; d < dims; d++) {
            int diff = query[d] - buffer.get(b + d);
            dist += diff * diff;
        }
        return dist;
    }

    @Override
    public double calculateI16(short[] query, ByteBuffer buffer, long base, int dims) {
        // Buffer em LITTLE_ENDIAN (índice V2). Máx 14 × 20 000² = 5,6×10⁹ — acumula em long.
        int b = (int) base;
        long dist = 0;
        for (int d = 0; d < dims; d++) {
            int diff = query[d] - buffer.getShort(b + d * 2);
            dist += (long) diff * diff;
        }
        return dist;
    }
}
