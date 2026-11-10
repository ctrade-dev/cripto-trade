package com.marmitt.core.application.usecase.websocket;

import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.enums.RunnerStatus;

import java.util.Set;

/**
 * Quais runners precisam das conexoes da sua exchange de pe: market data de cada fonte em
 * {@code allowedMarketDataSources} e o stream de usuario. Usado pelo coordenador de market data (abre o que
 * falta) e pelo estado consolidado ({@code STREAM_DISCONNECTED}), para os dois enxergarem a mesma demanda.
 *
 * <p>{@code ACTIVE} e {@code TERMINATING} negociam. {@code HALTED} tambem precisa (T53 F23): o kill switch e o
 * Safe Mode deixam ordens LIMIT no livro, cujo preenchimento so chega pelo stream de usuario, e a retomada
 * exige o envio de ordens liberado, ou seja, os canais perdidos de volta. Sem essa demanda, uma queda longa
 * terminava em {@code HALT} automatico sem ninguem reabrir o stream de usuario, e a retomada era recusada.
 * O roteamento do tick continua filtrando por status: runner parado nao negocia.
 */
public final class StreamDemand {

    private static final Set<RunnerStatus> DEMANDING = Set.of(
            RunnerStatus.ACTIVE, RunnerStatus.TERMINATING, RunnerStatus.HALTED);

    private StreamDemand() {
    }

    public static boolean needsStreams(StrategyRunner runner) {
        return DEMANDING.contains(runner.getStatus());
    }
}
