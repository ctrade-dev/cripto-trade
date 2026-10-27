package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.application.exception.CapitalReservationRejectedException;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.capital.CapitalRequest;
import com.marmitt.core.enums.RejectionReason;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * Valida as pre-condicoes de risco e efetua a reserva atomica de capital para ordens BUY.
 *
 * <p>As verificacoes sao executadas nesta sequencia intencional:
 * <ol>
 *   <li><b>Portfolio existe:</b> sem portfolio nao ha como saber a politica de risco
 *       nem o saldo disponivel. Falha com {@code UNKNOWN_RUNNER}.</li>
 *   <li><b>Safe Mode inativo:</b> quando o portfolio esta em Safe Mode, nenhum novo BUY
 *       e permitido independente do saldo. Falha com {@code RISK_VIOLATION}.</li>
 *   <li><b>GlobalBalance existe:</b> inconsistencia de dados — portfolio sem saldo nao
 *       deve ocorrer em operacao normal. Falha com {@code INSUFFICIENT_FUNDS}.</li>
 *   <li><b>Limite de exposicao do runner:</b> o valor do novo BUY nao pode passar do espaco do
 *       runner ({@link RunnerExposureService#allocationRoom}: {@code maxAllocationPercent × totalBalance}
 *       menos o custo do lote ativo e as BUY em voo, T62). Falha com {@code RUNNER_LIMIT_EXCEEDED}.</li>
 *   <li><b>Reserva atomica:</b> decrementa o saldo disponivel no banco via CAS. Falha
 *       com {@code INSUFFICIENT_FUNDS} se o saldo disponivel for insuficiente.</li>
 * </ol>
 *
 * <p>Qualquer falha lanca {@link com.marmitt.core.application.exception.CapitalReservationRejectedException},
 * capturada pelo {@link BuySignalHandler} que descarta o sinal sem propagar o erro.
 *
 * <p>O parametro {@code precomputedExposure} permite reaproveitar o valor ja calculado
 * pelo {@link RunnerExposureService#loadSnapshot} no pipeline BUY, evitando uma segunda consulta
 * ao banco.
 */
@Slf4j
class CapitalReservationPolicy {

    private final PortfolioRepositoryPort portfolioRepository;
    private final GlobalBalanceRepositoryPort globalBalanceRepository;
    private final RunnerExposureService exposureService;

    public CapitalReservationPolicy(PortfolioRepositoryPort portfolioRepository,
                                    GlobalBalanceRepositoryPort globalBalanceRepository,
                                    RunnerExposureService exposureService) {
        this.portfolioRepository = portfolioRepository;
        this.globalBalanceRepository = globalBalanceRepository;
        this.exposureService = exposureService;
    }

    /**
     * Executa todas as validacoes de risco e efetua a reserva atomica de capital.
     *
     * @param capitalRequest     pedido com valor, runner e transacao de referencia
     * @param runner             runner que originou o sinal BUY
     * @param precomputedExposure exposicao do runner ja calculada, ou {@code null} para
     *                           calcular sob demanda via {@link RunnerExposureService}
     * @throws com.marmitt.core.application.exception.CapitalReservationRejectedException
     *         se qualquer regra de aprovacao falhar
     */
    public void validateAndReserve(CapitalRequest capitalRequest,
                                   StrategyRunner runner,
                                   BigDecimal precomputedExposure) {
        Portfolio portfolio = portfolioRepository.findById(runner.getPortfolioId()).orElse(null);
        if (portfolio == null) {
            log.error("persistBuyAndReserve: portfolio {} not found for runner {}",
                    runner.getPortfolioId(), runner.getId());
            throw new CapitalReservationRejectedException(capitalRequest.transactionId(), RejectionReason.UNKNOWN_RUNNER);
        }
        if (portfolio.getSafeModeStatus().isActive()) {
            log.warn("persistBuyAndReserve: safe mode {} active for portfolio {}",
                    portfolio.getSafeModeStatus(), runner.getPortfolioId());
            throw new CapitalReservationRejectedException(capitalRequest.transactionId(), RejectionReason.RISK_VIOLATION);
        }

        GlobalBalance balance = globalBalanceRepository.findByPortfolioId(runner.getPortfolioId()).orElse(null);
        if (balance == null) {
            log.error("persistBuyAndReserve: global balance not found for portfolio {}",
                    runner.getPortfolioId());
            throw new CapitalReservationRejectedException(capitalRequest.transactionId(), RejectionReason.INSUFFICIENT_FUNDS);
        }

        BigDecimal currentExposure = precomputedExposure != null
                ? precomputedExposure
                : exposureService.loadSnapshot(runner).exposure();
        BigDecimal room = RunnerExposureService.allocationRoom(runner, balance, currentExposure);
        if (capitalRequest.amount().compareTo(room) > 0) {
            log.warn("persistBuyAndReserve: RUNNER_LIMIT_EXCEEDED runner={} currentExposure={} requested={} room={}",
                    runner.getId(), currentExposure, capitalRequest.amount(), room);
            throw new CapitalReservationRejectedException(capitalRequest.transactionId(), RejectionReason.RUNNER_LIMIT_EXCEEDED);
        }

        boolean reserved = globalBalanceRepository.reserveAtomic(runner.getPortfolioId(), capitalRequest.amount());
        if (!reserved) {
            log.warn("persistBuyAndReserve: INSUFFICIENT_FUNDS atomic reserve failed for portfolio={} amount={}",
                    runner.getPortfolioId(), capitalRequest.amount());
            throw new CapitalReservationRejectedException(capitalRequest.transactionId(), RejectionReason.INSUFFICIENT_FUNDS);
        }
    }
}

