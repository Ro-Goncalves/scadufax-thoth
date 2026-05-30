package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MmapBruteForceSearcherTest {

    // O JUnit gerencia essa pasta. Cria antes do teste, apaga depois.
    @TempDir
    Path tempDir;

    private File tempBinFile;
    private DistanceCalculator distanceCalculator;

    @BeforeEach
    void setUp() throws IOException {        
        distanceCalculator = new EuclideanDistanceCalculator();      
        tempBinFile = tempDir.resolve("mini-dataset.bin").toFile();
      
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(tempBinFile)))) {
            
            // Vetor 1: Distância = 1.0 (1^2 + 0)
            writeRecord(dos, "legit-A", new float[]{1.0f, 0.0f});
            
            // Vetor 2: Distância = 25.0 (3^2 + 4^2)
            writeRecord(dos, "fraud-B", new float[]{3.0f, 4.0f});
            
            // Vetor 3: Distância = 4.0 (0 + 2^2)
            writeRecord(dos, "legit-C", new float[]{0.0f, 2.0f});
            
            // Vetor 4: Distância = 200.0 (10^2 + 10^2) - Longe demais!
            writeRecord(dos, "fraud-D", new float[]{10.0f, 10.0f});
        }
    }

    // Método auxiliar para simular o nosso gerador TLV (Type-Length-Value)
    private void writeRecord(DataOutputStream dos, String label, float[] vector) throws IOException {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(labelBytes.length);
        dos.write(labelBytes);
        dos.writeInt(vector.length);
        for (float v : vector) {
            dos.writeFloat(v);
        }
    }

    @Test
    @DisplayName("Deve retornar apenas os Top K vizinhos mais próximos ordenados pela menor distância")
    void testSearchReturnsTopK() throws Exception {
        // O vetor de busca será a origem (0,0) para facilitar nossa matemática mental
        float[] queryVector = {0.0f, 0.0f};
        int k = 2; // Queremos apenas os 2 mais próximos de um total de 4
       
        try (Arena arena = Arena.ofConfined()) {
            MmapBruteForceSearcher searcher = new MmapBruteForceSearcher(
                    tempBinFile.getAbsolutePath(), 
                    arena, 
                    distanceCalculator
            );
          
            List<SearchResult> results = searcher.search(queryVector, k);
           
            assertEquals(2, results.size(), "Deve retornar exatamente K elementos");
           
            assertEquals("legit-A", results.get(0).label());
            assertEquals(1.0, results.get(0).distance(), 0.0001);
           
            assertEquals("legit-C", results.get(1).label());
            assertEquals(4.0, results.get(1).distance(), 0.0001);

            // O fraud-B (dist: 25) e fraud-D (dist: 200) devem ter sido descartados pelo Max-Heap!
        }
    }
}