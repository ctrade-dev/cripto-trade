package com.marmitt.core.dto.wrapper;

import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.common.CurrencyPair;
import com.marmitt.core.dto.connection.ConnectionStatsDto;
import com.marmitt.core.dto.websocket.request.MessageRequest;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.StreamAction;
import com.marmitt.core.exceptions.IllegalStateTransitionException;
import lombok.Getter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class WebSocketConnectionManager {

    @Getter
    private final String exchangeName;
    private final ConnectionStatsDto currentConnectionStats;
    private volatile ConnectionResultDto currentConnectionResult;
    @Getter
    private final List<MessageRequest> requestHistory;

    private WebSocketConnectionManager(ConnectionResultDto connectionResult, String exchangeName) {
        this.exchangeName = exchangeName;
        this.currentConnectionResult = connectionResult;
        this.currentConnectionStats = createStatsForExchange();
        this.requestHistory = new CopyOnWriteArrayList<>(); // lido pelo coordenador enquanto outros caminhos assinam
    }

    public static WebSocketConnectionManager forExchange(String exchangeName) {
        return new WebSocketConnectionManager(ConnectionResultDto.idle(), exchangeName);
    }
    
    private static ConnectionStatsDto createStatsForExchange() {
        return ConnectionStatsDto.empty();
    }

    public void setConnectionResult(ConnectionResultDto newResult) {
        ConnectionStatus currentStatus = currentConnectionResult.status();
        ConnectionStatus newStatus = newResult.status();
        
        // Validação de transições válidas
        if (!isValidTransition(currentStatus, newStatus)) {
            throw new IllegalStateTransitionException(
                String.format("Invalid transition from %s to %s", currentStatus, newStatus)
            );
        }
        
        // Atualiza estatísticas baseado na transição
        updateStatsForTransition(currentStatus, newStatus);
        
        // Atualiza o resultado
        this.currentConnectionResult = newResult;
    }

    public void addRequestToHistory(MessageRequest request) {
        requestHistory.add(request);
    }

    public MessageRequest getLastRequestHistory() {
        return requestHistory.getLast();
    }

    /**
     * A assinatura acumulada da conexao: os pares de todos os SUBSCRIBE do historico, na ordem em que
     * entraram, menos os que um UNSUBSCRIBE posterior tirou. E o que uma reconexao precisa reabrir: o ultimo
     * pedido sozinho pode ser a assinatura de um simbolo so (T53 F28). Vazio quando nada foi assinado.
     */
    public Optional<StreamSubscriptionRequest> accumulatedSubscription() {
        LinkedHashSet<CurrencyPair> pairs = new LinkedHashSet<>();
        String exchange = null;
        for (MessageRequest request : requestHistory) {
            if (request instanceof StreamSubscriptionRequest subscription) {
                exchange = subscription.getExchangeName();
                if (subscription.getStreamAction() == StreamAction.SUBSCRIBE) {
                    pairs.addAll(subscription.getCurrencyPairs());
                } else {
                    subscription.getCurrencyPairs().forEach(pairs::remove);
                }
            }
        }
        if (exchange == null) {
            return Optional.empty();
        }
        return Optional.of(new StreamSubscriptionRequest(exchange, List.copyOf(pairs), StreamAction.SUBSCRIBE));
    }

    public UUID getConnectionId() {
        return getConnectionResult().connectionId();
    }

    public ConnectionStatsDto getConnectionStats() {
        return currentConnectionStats;
    }

    public ConnectionResultDto getConnectionResult() {
        return currentConnectionResult;
    }

    /**
     * O evento veio de uma conexao que ja foi substituida (close ou falha tardia de um socket antigo)?
     * Esse evento nao diz nada sobre a conexao atual.
     */
    public boolean isSupersededBy(UUID eventConnectionId) {
        ConnectionStatus status = currentConnectionResult.status();
        if (status != ConnectionStatus.RECONNECTING && status != ConnectionStatus.CONNECTED) {
            return false;
        }
        UUID current = currentConnectionResult.connectionId();
        return current != null && !current.equals(eventConnectionId);
    }

    public void onMessageReceived() {
        currentConnectionStats.recordMessage();
    }

    public void onMessageError(String errorType) {
        currentConnectionStats.recordError();
    }

    public void resetConnection() {
        if (currentConnectionResult.status() == ConnectionStatus.ERROR ||
                currentConnectionResult.status() == ConnectionStatus.CLOSED) {

            this.currentConnectionResult = ConnectionResultDto.idle();
            currentConnectionStats.resetCounters();
        }
    }

    private boolean isValidTransition(ConnectionStatus from, ConnectionStatus to) {
        return switch (from) {
            case IDLE -> Set.of(ConnectionStatus.CONNECTING, ConnectionStatus.ERROR).contains(to);
            case CONNECTING -> Set.of(ConnectionStatus.CONNECTED, ConnectionStatus.ERROR, ConnectionStatus.DISCONNECTED, ConnectionStatus.CLOSING).contains(to);
            case CONNECTED -> Set.of(ConnectionStatus.CLOSING, ConnectionStatus.DISCONNECTING, ConnectionStatus.ERROR).contains(to);
            case CLOSING -> Set.of(ConnectionStatus.CLOSED, ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR).contains(to);
            case DISCONNECTING -> Set.of(ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR).contains(to);
            case DISCONNECTED -> Set.of(ConnectionStatus.CONNECTING, ConnectionStatus.IDLE, ConnectionStatus.ERROR).contains(to);
            case RECONNECTING -> Set.of(ConnectionStatus.CONNECTED, ConnectionStatus.ERROR, ConnectionStatus.DISCONNECTED).contains(to);
            // ERROR pode ir para CLOSED porque OkHttp dispara onFailure (→ ERROR) e onClosed (→ CLOSED) em sequência
            case ERROR -> Set.of(ConnectionStatus.RECONNECTING, ConnectionStatus.IDLE, ConnectionStatus.CONNECTING, ConnectionStatus.CLOSED).contains(to);
            case CLOSED -> Set.of(ConnectionStatus.RECONNECTING, ConnectionStatus.IDLE, ConnectionStatus.CONNECTING).contains(to);
        };
    }

    private void updateStatsForTransition(ConnectionStatus from, ConnectionStatus to) {
        switch (from) {
            case CONNECTING -> handleFromConnecting(to);
            case CONNECTED -> handleFromConnected(to);
            case DISCONNECTING -> handleFromDisconnecting(to);
            case RECONNECTING -> handleFromReconnecting(to);
        }
        
        if (to == ConnectionStatus.ERROR) {
            currentConnectionStats.recordError();
        }
    }
    
    private void handleFromConnecting(ConnectionStatus to) {
        if (to == ConnectionStatus.CONNECTED) {
            currentConnectionStats.recordConnection();
        }
    }
    
    private void handleFromConnected(ConnectionStatus to) {
        if (to == ConnectionStatus.ERROR) {
            currentConnectionStats.recordDisconnection();
        }

        if (to == ConnectionStatus.DISCONNECTING || to == ConnectionStatus.CLOSING) {
            // Não registra disconnection aqui, só quando realmente desconectar
        }
    }
    
    private void handleFromDisconnecting(ConnectionStatus to) {
        if (to == ConnectionStatus.DISCONNECTED) {
            currentConnectionStats.recordDisconnection();
        }
    }
    
    private void handleFromReconnecting(ConnectionStatus to) {
        if (to == ConnectionStatus.CONNECTED) {
            currentConnectionStats.recordReconnection();
        }
    }
}
