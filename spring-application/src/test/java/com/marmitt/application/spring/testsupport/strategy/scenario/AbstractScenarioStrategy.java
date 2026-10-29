package com.marmitt.application.spring.testsupport.strategy.scenario;

import com.marmitt.core.dto.strategy.PendingOrderDto;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.enums.TradingAction;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base das estratégias de cenário determinísticas (T35). Concentra identidade (id/nome/versão),
 * o estado {@code enabled} e helpers de preço/estado; cada subclasse implementa apenas a decisão
 * do seu cenário.
 *
 * <p>São puras decisões — sem infra, rede ou schema de exchange. Dublês de teste (T65): cada teste
 * registra a sua no repositório de estratégias; o ctrade de produção não embute estratégia.
 */
abstract class AbstractScenarioStrategy implements TradingStrategy {

    protected static final BigDecimal CONFIDENCE = BigDecimal.ONE;
    private static final int PRICE_SCALE = 8;

    private final UUID strategyId;
    private final String strategyName;
    private final String strategyVersion;
    protected final ScenarioStrategyConfig config;
    // Orçamento de ciclos escopado por runner: a instância é um singleton resolvido no
    // StrategyRepository para todo runner, então um contador único vazaria o teto entre runners
    // (o primeiro esgotaria e os demais nasceriam inertes). Cada runnerId tem o seu próprio contador,
    // mantendo o contrato "N ciclos por runner" independente de ordem/compartilhamento.
    private final ConcurrentMap<UUID, AtomicInteger> cyclesByRunner = new ConcurrentHashMap<>();
    private boolean enabled = true;

    protected AbstractScenarioStrategy(UUID strategyId, String strategyName,
                                       String strategyVersion, ScenarioStrategyConfig config) {
        this.strategyId = strategyId;
        this.strategyName = strategyName;
        this.strategyVersion = strategyVersion;
        this.config = config;
    }

    @Override
    public UUID getStrategyId() {
        return strategyId;
    }

    @Override
    public String getStrategyName() {
        return strategyName;
    }

    @Override
    public String getStrategyVersion() {
        return strategyVersion;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Consome um ciclo do orçamento do {@code runnerId}, se houver. Retorna {@code true} (e
     * incrementa) quando o runner ainda pode iniciar um novo ciclo; {@code false} quando o teto
     * {@code maxCycles} já foi atingido para aquele runner — sinal para a estratégia ficar inerte
     * (HOLD). Sem teto ({@code maxCycles=0}) sempre retorna {@code true}. Cada subclasse chama isto
     * no ponto que inicia o seu ciclo (abertura de BUY, colocação de SELL descansando, etc.), não na
     * limpeza (cancelamento).
     */
    protected boolean tryStartCycle(UUID runnerId) {
        if (!config.hasCycleLimit()) {
            return true;
        }
        int limit = config.maxCycles();
        AtomicInteger counter = cyclesByRunner.computeIfAbsent(runnerId, k -> new AtomicInteger(0));
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Número de ciclos já iniciados pelo runner (para observabilidade/teste). */
    protected int cyclesStarted(UUID runnerId) {
        AtomicInteger counter = cyclesByRunner.get(runnerId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * {@code true} quando há teto e ele já foi totalmente consumido pelo {@code runnerId}. Ao
     * contrário de {@link #tryStartCycle(UUID)}, <b>não</b> consome orçamento — serve para legs de
     * setup (ex.: reabertura de posição) ficarem inertes após o teto sem serem contadas como ciclo.
     */
    protected boolean budgetExhausted(UUID runnerId) {
        return config.hasCycleLimit() && cyclesStarted(runnerId) >= config.maxCycles();
    }

    /** Primeira ordem em trânsito do lado informado (BUY/SELL), se houver. */
    protected Optional<PendingOrderDto> firstPendingOfType(StrategyContextDto context, TradingAction type) {
        return context.pendingOrders().stream()
                .filter(pending -> pending.type() == type)
                .findFirst();
    }

    /** Preço abaixo do mercado por uma fração (ex.: descansar um BUY ou tornar um SELL marketable). */
    protected BigDecimal priceBelow(BigDecimal price, BigDecimal offsetFraction) {
        return scale(price.multiply(BigDecimal.ONE.subtract(offsetFraction)));
    }

    /** Preço acima do mercado por uma fração (ex.: descansar um SELL ou tornar um BUY marketable). */
    protected BigDecimal priceAbove(BigDecimal price, BigDecimal offsetFraction) {
        return scale(price.multiply(BigDecimal.ONE.add(offsetFraction)));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
