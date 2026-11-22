package com.marmitt.application.spring.controller;

import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.dto.portfolio.response.AggregatedTransactionsResponse;
import com.marmitt.core.dto.portfolio.response.GlobalBalanceDto;
import com.marmitt.core.dto.portfolio.response.PortfolioDto;
import com.marmitt.core.dto.portfolio.response.PortfolioPnlDto;
import com.marmitt.core.dto.portfolio.response.TransactionDto;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.portfolio.QueryPortfolioPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/portfolios")
public class PortfolioController {

    private final CreatePortfolioPort createPortfolio;
    private final QueryPortfolioPort queryPortfolio;

    public PortfolioController(
            CreatePortfolioPort createPortfolio,
            QueryPortfolioPort queryPortfolio
    ) {
        this.createPortfolio = createPortfolio;
        this.queryPortfolio = queryPortfolio;
    }

    /**
     * Cria novo portfolio
     * POST /portfolios
     */
    @PostMapping
    public ResponseEntity<CreatePortfolioResponse> createPortfolio(
            @RequestBody CreatePortfolioRequest request
    ) {
        log.info("Received request to create portfolio - Name: {}, InitialCapital: {}, Currency: {}",
                request.name(), request.initialCapitalAmount(), request.currency());

        CreatePortfolioResponse response = createPortfolio.execute(request);

        if (response.portfolioId() == null) {
            throw new IllegalArgumentException(response.message());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Busca portfolio por ID
     * GET /portfolios/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<PortfolioDto> getPortfolioById(@PathVariable UUID id) {
        log.debug("Received request to get portfolio by ID: {}", id);

        Optional<PortfolioDto> portfolioOpt = queryPortfolio.findById(id);

        if (portfolioOpt.isEmpty()) {
            log.warn("Portfolio not found with ID: {}", id);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(portfolioOpt.get());
    }

    /**
     * Lista transações agregadas dos runners de um portfolio, por {@code requestedAt}, com a decisão da estratégia
     * (reasoning, metadata). {@code from} (inclusivo) e {@code to} (exclusivo), ISO-8601, limitam o período (T74).
     * GET /portfolios/{id}/transactions?from=&to=
     */
    @GetMapping("/{id}/transactions")
    public ResponseEntity<AggregatedTransactionsResponse> getTransactionsByPortfolioId(
            @PathVariable UUID id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        log.debug("Received request to get transactions for portfolio: {}", id);

        Optional<PortfolioDto> portfolioOpt = queryPortfolio.findById(id);

        if (portfolioOpt.isEmpty()) {
            log.warn("Portfolio not found with ID: {}", id);
            return ResponseEntity.notFound().build();
        }

        PortfolioDto portfolio = portfolioOpt.get();
        List<TransactionDto> transactions = queryPortfolio.findTransactionsByPortfolioId(id, from, to);

        AggregatedTransactionsResponse response = new AggregatedTransactionsResponse(
                portfolio.id(),
                portfolio.name(),
                transactions,
                transactions.size()
        );

        log.debug("Found {} transactions for portfolio: {}", transactions.size(), id);
        return ResponseEntity.ok(response);
    }

    /**
     * Retorna o GlobalBalance de um portfolio
     * GET /portfolios/{id}/balance
     */
    @GetMapping("/{id}/balance")
    public ResponseEntity<GlobalBalanceDto> getBalanceByPortfolioId(@PathVariable UUID id) {
        log.debug("Received request to get balance for portfolio: {}", id);

        Optional<GlobalBalanceDto> balanceOpt = queryPortfolio.findBalanceByPortfolioId(id);
        if (balanceOpt.isEmpty()) {
            log.warn("GlobalBalance not found for portfolio: {}", id);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(balanceOpt.get());
    }

    /**
     * Retorna o PnL consolidado de um portfolio
     * GET /portfolios/{id}/pnl
     */
    @GetMapping("/{id}/pnl")
    public ResponseEntity<PortfolioPnlDto> getPnlByPortfolioId(@PathVariable UUID id) {
        log.debug("Received request to get PnL for portfolio: {}", id);

        Optional<PortfolioPnlDto> pnlOpt = queryPortfolio.findPnlByPortfolioId(id);
        if (pnlOpt.isEmpty()) {
            log.warn("PnL not found for portfolio: {}", id);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(pnlOpt.get());
    }

}

