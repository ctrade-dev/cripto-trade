package com.marmitt.application.spring.testsupport.strategy.sma;

import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registra a SMA de teste no repositorio de estrategias do contexto (T65). O ctrade nao embute
 * estrategia; os testes de integracao que criam runners com o id da SMA dependem deste registro.
 */
@TestConfiguration
public class SmaTestStrategyConfiguration {

    @Bean
    InitializingBean smaTestStrategyRegistration(StrategyRepositoryPort strategies) {
        return () -> strategies.registerStrategy(new SimpleMovingAverageStrategy());
    }
}
