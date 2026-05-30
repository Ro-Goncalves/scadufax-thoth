package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

public class MmapBruteForceSearcher implements VectorSearcher {

    private final MemorySegment segment;
    private final long fileSize;
    private final DistanceCalculator distanceCalculator;

    private static final ValueLayout.OfInt JAVA_INT_BE_UNALIGNED = 
    ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    public MmapBruteForceSearcher(String binFilePath, Arena arena, DistanceCalculator distanceCalculator) throws Exception {
        this.distanceCalculator = distanceCalculator;
        try (FileChannel channel = FileChannel.open(Path.of(binFilePath), StandardOpenOption.READ)) {
            this.fileSize = channel.size();
            this.segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, this.fileSize, arena);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, int k) {
        PriorityQueue<SearchResult> pq = new PriorityQueue<>(k);
        long offset = 0;

        while (offset < fileSize) {
            // Lê Tamanho do Label
            int labelLen = segment.get(JAVA_INT_BE_UNALIGNED, offset);
            offset += 4;

            // Regista a posição do Label para leitura posterior, 
            // mas avança o offset principal para ler o próximo campo
            long labelOffset = offset;
            offset += labelLen;

            // Lê Dimensões
            int dim = segment.get(JAVA_INT_BE_UNALIGNED, offset);
            offset += 4;

            double distance = distanceCalculator.calculate(queryVector, segment, offset, dim);

            // Avança o offset principal passando por todos os floats que acabaram de ser lidos
            offset += (dim * 4L);

            // Gestão do Top K
            if (pq.size() < k) {
                pq.offer(new SearchResult(extractString(labelOffset, labelLen), distance));
            } else if (distance < pq.peek().distance()) {
                pq.poll();
                pq.offer(new SearchResult(extractString(labelOffset, labelLen), distance));
            }
        }

        List<SearchResult> results = new ArrayList<>(pq);
        results.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        return results;
    }

    private String extractString(long offset, int length) {
        MemorySegment labelSegment = segment.asSlice(offset, length);
        return new String(labelSegment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }
}