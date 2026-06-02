package br.com.rgbrainlabs.scadufaxthoth.search;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

public class EuclideanDistanceCalculator implements DistanceCalculator {

    private static final ValueLayout.OfFloat JAVA_FLOAT_BE_UNALIGNED = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort JAVA_SHORT_LE_UNALIGNED = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @Override
    public double calculate(float[] queryVector, MemorySegment segment, long offset, int dimensions) {
        double squaredDistance = 0.0;
        long currentOffset = offset; // Usamos uma variável local para não alterar o offset do buscador

        for (int i = 0; i < dimensions; i++) {
            float vectorVal = segment.get(JAVA_FLOAT_BE_UNALIGNED, currentOffset);
            float diff = queryVector[i] - vectorVal;
            squaredDistance += (diff * diff);
            currentOffset += 4; // Avança 4 bytes (tamanho de um float)
        }

        return squaredDistance;
    }

    @Override
    public double calculateI8(byte[] query, MemorySegment segment, long base, int dims) {
        // Opera no espaço inteiro: evita conversão para float no hot loop
        int dist = 0;
        for (int d = 0; d < dims; d++) {
            int diff = query[d] - segment.get(ValueLayout.JAVA_BYTE, base + d);
            dist += diff * diff;
        }
        return dist;
    }

    @Override
    public double calculateI16(short[] query, MemorySegment segment, long base, int dims) {
        // Máx 14 × 20 000² = 5,6 × 10⁹ — ultrapassa int, opera em long
        long dist = 0;
        for (int d = 0; d < dims; d++) {
            int diff = query[d] - segment.get(JAVA_SHORT_LE_UNALIGNED, base + d * 2L);
            dist += (long) diff * diff;
        }
        return dist;
    }
}