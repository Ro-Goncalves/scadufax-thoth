package br.com.rgbrainlabs.scadufaxthoth.search;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

public class EuclideanDistanceCalculator implements DistanceCalculator {

    private static final ValueLayout.OfFloat JAVA_FLOAT_BE_UNALIGNED = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

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
}