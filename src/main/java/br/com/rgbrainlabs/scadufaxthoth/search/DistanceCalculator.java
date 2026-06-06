package br.com.rgbrainlabs.scadufaxthoth.search;

import java.nio.ByteBuffer;

public interface DistanceCalculator {
    /**
     * Calcula a distância entre o vetor de busca e o vetor armazenado no buffer mapeado.
     *
     * @param queryVector O vetor recebido na requisição HTTP.
     * @param buffer      O buffer mapeado do ficheiro binário (MappedByteBuffer).
     * @param offset      A posição exata onde começam os floats deste vetor específico.
     * @param dimensions  A quantidade de dimensões a ler.
     * @return O valor da distância calculada.
     */
    double calculate(float[] queryVector, ByteBuffer buffer, long offset, int dimensions);

    /**
     * Calcula a distância entre um vetor de busca quantizado em int8 e um candidato no buffer.
     * Opera inteiramente no espaço inteiro para preservar o ganho de desempenho da quantização.
     *
     * @param query  Vetor de busca quantizado (byte por dimensão).
     * @param buffer Buffer mapeado contendo os vetores quantizados do dataset.
     * @param base   Índice de byte do início do vetor candidato no buffer.
     * @param dims   Número de dimensões.
     * @return O valor da distância calculada.
     */
    double calculateI8(byte[] query, ByteBuffer buffer, long base, int dims);

    /**
     * Calcula a distância entre um vetor de busca quantizado em int16 e um candidato no buffer.
     * Opera inteiramente no espaço inteiro para preservar o ganho de desempenho da quantização.
     *
     * <p>O {@code buffer} DEVE estar em {@link java.nio.ByteOrder#LITTLE_ENDIAN} (encoding do
     * artefato V2): o loop usa {@code getShort} absoluto, que o GraalVM Native Image
     * auto-vetoriza — ao contrário do acesso via {@code MemorySegment} (FFM), ~14x mais lento
     * no AOT sem PGO.
     *
     * @param query  Vetor de busca quantizado (short por dimensão).
     * @param buffer Buffer mapeado (LITTLE_ENDIAN) contendo os vetores quantizados.
     * @param base   Índice de byte do início do vetor candidato no buffer.
     * @param dims   Número de dimensões.
     * @return O valor da distância calculada.
     */
    double calculateI16(short[] query, ByteBuffer buffer, long base, int dims);
}
