package br.com.rgbrainlabs.scadufaxthoth.search;

import java.lang.foreign.MemorySegment;

public interface DistanceCalculator {
    /**
     * Calcula a distância entre o vetor de busca e o vetor armazenado em memória nativa.
     *
     * @param queryVector O vetor recebido na requisição HTTP.
     * @param segment     A memória mapeada do ficheiro binário.
     * @param offset      A posição exata onde começam os floats deste vetor específico.
     * @param dimensions  A quantidade de dimensões a ler.
     * @return O valor da distância calculada.
     */
    double calculate(float[] queryVector, MemorySegment segment, long offset, int dimensions);

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
    double calculateI8(byte[] query, MemorySegment segment, long base, int dims);

    /**
     * Calcula a distância entre um vetor de busca quantizado em int16 e um candidato no buffer.
     * Opera inteiramente no espaço inteiro para preservar o ganho de desempenho da quantização.
     *
     * @param query  Vetor de busca quantizado (short por dimensão).
     * @param buffer Buffer mapeado contendo os vetores quantizados do dataset.
     * @param base   Índice de byte do início do vetor candidato no buffer.
     * @param dims   Número de dimensões.
     * @return O valor da distância calculada.
     */
    double calculateI16(short[] query, MemorySegment segment, long base, int dims);
}