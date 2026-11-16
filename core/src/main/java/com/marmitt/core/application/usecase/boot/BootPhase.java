package com.marmitt.core.application.usecase.boot;

import com.marmitt.core.dto.boot.BootExecutionCommand;

/**
 * Uma fase do boot (T70). A sequencia ({@link RunBootSequenceUseCase}) so ordena, liga/desliga, cronometra e
 * trata a falha; o corpo de cada fase fica na sua classe, no pacote da fase.
 */
public interface BootPhase {

    /** Nome da fase nos logs, nas metricas, no {@code GET} do boot e no historico, por exemplo {@code phase2.sanity}. */
    String name();

    boolean enabled(BootExecutionCommand command);

    /** Executa a fase; excecao falha a fase e o boot. */
    void run(BootContext context);
}
