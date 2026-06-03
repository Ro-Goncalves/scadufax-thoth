package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuantizedBruteForceSearcherTest {

    @TempDir
    Path dataDir;

    private static final int DIMS = 14;

    // Dataset: 5 vetores, só a primeira dimensão varia.
    //
    // idx | first dim | i8  | i16  | label
    //  0  |   0.0     |   0 |    0 | legitimate
    //  1  |   0.2     |  25 | 2000 | legitimate
    //  2  |   0.4     |  51 | 4000 | fraud
    //  3  |   0.6     |  76 | 6000 | fraud
    //  4  |   0.8     | 102 | 8000 | fraud
    //
    // labels.bin: bits 0,1=0 (legit); bits 2,3,4=1 (fraud) → 0b00011100 = 28

    private static final byte[]  I8_FIRST_DIM  = {0, 25, 51, 76, 102};
    private static final short[] I16_FIRST_DIM = {0, 2000, 4000, 6000, 8000};

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dataDir.resolve("meta.properties"),
                "count=5\nscale_i8=127\nscale_i16=10000\ndims=14\n");

        // vectors-i8.bin: 5 × 14 bytes
        byte[] i8Data = new byte[5 * DIMS];
        for (int i = 0; i < 5; i++) {
            i8Data[i * DIMS] = I8_FIRST_DIM[i];
        }
        Files.write(dataDir.resolve("vectors-i8.bin"), i8Data);

        // vectors-i16.bin: 5 × 14 × 2 bytes, little-endian
        ByteBuffer i16Buf = ByteBuffer.allocate(5 * DIMS * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 5; i++) {
            i16Buf.putShort(I16_FIRST_DIM[i]);
            for (int d = 1; d < DIMS; d++) i16Buf.putShort((short) 0);
        }
        Files.write(dataDir.resolve("vectors-i16.bin"), i16Buf.array());

        // labels.bin: 0b00011100 = 28
        Files.write(dataDir.resolve("labels.bin"), new byte[]{0b00011100});
    }

    @Test
    void searchI8RetornaTop3MaisProximos() throws Exception {
        float[] query = new float[DIMS]; // todos zeros
        try (QuantizedBruteForceSearcher s = new QuantizedBruteForceSearcher(
                dataDir, QuantizedBruteForceSearcher.Dtype.I8, new EuclideanDistanceCalculator())) {

            List<SearchResult> results = s.search(query, 3);

            assertEquals(3, results.size());
            // idx 0: dist = 0
            assertEquals(0.0,   results.get(0).distance(), 0.0);
            assertEquals("legitimate", results.get(0).label());
            // idx 1: dist = 25² = 625
            assertEquals(625.0, results.get(1).distance(), 0.0);
            assertEquals("legitimate", results.get(1).label());
            // idx 2: dist = 51² = 2601
            assertEquals(2601.0, results.get(2).distance(), 0.0);
            assertEquals("fraud", results.get(2).label());
        }
    }

    @Test
    void searchI16RetornaTop3MaisProximos() throws Exception {
        float[] query = new float[DIMS]; // todos zeros
        try (QuantizedBruteForceSearcher s = new QuantizedBruteForceSearcher(
                dataDir, QuantizedBruteForceSearcher.Dtype.I16, new EuclideanDistanceCalculator())) {

            List<SearchResult> results = s.search(query, 3);

            assertEquals(3, results.size());
            // idx 0: dist = 0
            assertEquals(0.0, results.get(0).distance(), 0.0);
            assertEquals("legitimate", results.get(0).label());
            // idx 1: dist = 2000² = 4_000_000
            assertEquals(4_000_000.0, results.get(1).distance(), 0.0);
            assertEquals("legitimate", results.get(1).label());
            // idx 2: dist = 4000² = 16_000_000
            assertEquals(16_000_000.0, results.get(2).distance(), 0.0);
            assertEquals("fraud", results.get(2).label());
        }
    }

    @Test
    void searchComKMaiorQueCountRetornaTodosOsVetores() throws Exception {
        float[] query = new float[DIMS];
        try (QuantizedBruteForceSearcher s = new QuantizedBruteForceSearcher(
                dataDir, QuantizedBruteForceSearcher.Dtype.I8, new EuclideanDistanceCalculator())) {
            List<SearchResult> results = s.search(query, 10);
            assertEquals(5, results.size());
        }
    }

    @Test
    void closeComArenaPropriaFechaSemErro() throws Exception {
        QuantizedBruteForceSearcher s = new QuantizedBruteForceSearcher(
                dataDir, QuantizedBruteForceSearcher.Dtype.I8, new EuclideanDistanceCalculator());
        assertDoesNotThrow(s::close);
    }
}
