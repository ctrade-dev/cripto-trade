package com.marmitt.core.application.handler.connection;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.events.WebSocketFailedEvent;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.ports.inbound.handler.ConnectionFailedPort;
import com.marmitt.core.ports.outbound.connection.ReconnectionStrategyPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConnectionFailedHandler implements ConnectionFailedPort {

    private final WebSocketConnectionRepositoryPort connectionRepository;
    private final ReconnectionStrategyPort reconnectionStrategy;

    public ConnectionFailedHandler(WebSocketConnectionRepositoryPort connectionRepository,
                                   ReconnectionStrategyPort reconnectionStrategy) {
        this.connectionRepository = connectionRepository;
        this.reconnectionStrategy = reconnectionStrategy;
    }

    @Override
    public void execute(final WebSocketFailedEvent event) {
        log.warn("WebSocket failed - exchange={} channel={} attempt={} critical={} reason={}",
                event.exchange(), event.channel(), event.attemptCount(), event.isCritical(), event.reason());
        try {
            ConnectionKey key = new ConnectionKey(event.exchange(), event.channel());
            WebSocketConnectionManager manager = connectionRepository.getConnection(key);
            if (manager.isSupersededBy(event.connectionId())) {
                log.debug("Ignoring stale failed event for old connection={} (manager is {} with connection={})",
                        event.connectionId(), manager.getConnectionResult().status(),
                        manager.getConnectionResult().connectionId());
                return;
            }

            ConnectionResultDto failure = event.cause() != null
                    ? ConnectionResultDto.failure(event.reason(), event.connectionId(), event.cause())
                    : ConnectionResultDto.failure(this.getClass().getSimpleName(), event.reason());
            manager.setConnectionResult(failure);

            if (!event.isCritical()) {
                reconnectionStrategy.scheduleReconnect(
                        event.exchange(), event.channel(), event.connectionId(), event.attemptCount());
            }

        } catch (Exception e) {
            log.error("Failed to process connection failed event - exchange={} channel={}", event.exchange(), event.channel(), e);
            throw new RuntimeException("Error processing connection failed event", e);
        }
    }
}
