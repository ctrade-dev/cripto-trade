package com.marmitt.core.ports.inbound.marketdata;

/** Mantem as series de candles que as estrategias declaram (T72). Chamado periodicamente. */
public interface EnsureCandleSeriesPort {

    void ensure();
}
