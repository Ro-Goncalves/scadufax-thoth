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
}