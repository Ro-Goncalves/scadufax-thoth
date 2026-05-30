package br.com.rgbrainlabs.scadufaxthoth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.nio.charset.StandardCharsets;

public class DatasetBuilder {
    
    public record ReferenceRow(List<Double> vector, String label) {}

    public static void main(String[] args) throws Exception {
        // Lê o dataset gzipado do diretório de recursos do projeto
        File jsonFile = new File("src/main/resources/references.json.gz");
        File binFile = new File("dataset.bin");

        System.out.println("Iniciando conversão STREAMING (JSON -> BIN)...");
        System.out.println("Entrada: " + jsonFile.getAbsolutePath());
        System.out.println("Saída: " + binFile.getAbsolutePath());
        long startTime = System.currentTimeMillis();

        ObjectMapper mapper = new ObjectMapper();
        int count = 0;

        try (InputStream inputStream = new GZIPInputStream(new FileInputStream(jsonFile));
             JsonParser jsonParser = mapper.getFactory().createParser(inputStream);
             DataOutputStream dos = new DataOutputStream(
                     new BufferedOutputStream(new FileOutputStream(binFile)))) {

            // Avança até o início do array JSON "["
            if (jsonParser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("O JSON deve ser um array de objetos.");
            }

            // Itera enquanto houver objetos "{" dentro do array
            while (jsonParser.nextToken() == JsonToken.START_OBJECT) {
                // Lê apenas UM objeto para a memória
                ReferenceRow row = mapper.readValue(jsonParser, ReferenceRow.class);

                // Grava tamanho e bytes do Label
                byte[] labelBytes = row.label().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(labelBytes.length);
                dos.write(labelBytes);

                // Grava a dimensão do vetor
                dos.writeInt(row.vector().size());

                // Grava os floats
                for (Double val : row.vector()) {
                    dos.writeFloat(val.floatValue());
                }

                count++;
                if (count % 100_000 == 0) {
                    System.out.println("Processados: " + count + " registros...");
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("✅ Arquivo dataset.bin gerado com sucesso em " + duration + "ms.");
        System.out.println("Total de registros processados: " + count);
        System.out.println("Tamanho final do arquivo: " + (binFile.length() / (1024 * 1024)) + " MB");
    }
}