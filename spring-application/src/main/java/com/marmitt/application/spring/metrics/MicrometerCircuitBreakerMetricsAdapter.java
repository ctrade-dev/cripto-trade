package com.marmitt.application.spring.metrics;

import com.marmitt.core.ports.outbound.metrics.CircuitBreakerMetricsPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gauges por portfolio do monitor de drawdown, contador de ativações (T43, IG §11.7) e de retomadas automáticas
 * do HALT (T71).
 */
@Component
public class MicrometerCircuitBreakerMetricsAdapter implements CircuitBreakerMetricsPort {

    static final String DRAWDOWN = "circuit_breaker.drawdown.current";
    static final String PEAK = "circuit_breaker.drawdown.peak_24h";
    static final String EQUITY = "circuit_breaker.equity.current";
    static final String ACTIVATIONS = "circuit_breaker.activations.total";
    static final String AUTO_RESUMES = "circuit_breaker.auto_resumes.total";

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    public MicrometerCircuitBreakerMetricsAdapter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordDrawdown(UUID portfolioId, BigDecimal equity, BigDecimal peak, BigDecimal drawdown) {
        gauge(DRAWDOWN, portfolioId).set(drawdown.doubleValue());
        gauge(PEAK, portfolioId).set(peak.doubleValue());
        gauge(EQUITY, portfolioId).set(equity.doubleValue());
    }

    @Override
    public void recordActivation(UUID portfolioId, String reason) {
        meterRegistry.counter(ACTIVATIONS,
                "portfolioId", portfolioId.toString(), "level", "HALT", "reason", reason).increment();
    }

    @Override
    public void recordAutoResume(UUID portfolioId, String trigger) {
        meterRegistry.counter(AUTO_RESUMES, "portfolioId", portfolioId.toString(), "trigger", trigger).increment();
    }

    private AtomicReference<Double> gauge(String name, UUID portfolioId) {
        return gauges.computeIfAbsent(name + ":" + portfolioId, key -> {
            AtomicReference<Double> holder = new AtomicReference<>(0.0);
            Gauge.builder(name, holder, AtomicReference::get)
                    .tag("portfolioId", portfolioId.toString())
                    .register(meterRegistry);
            return holder;
        });
    }
}
