package com.coinflow.recovery.service;

import com.coinflow.aggregation.service.KlineBroadcaster;
import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.ohlc.constant.OhlcWindowPolicy;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.recovery.service.RecoveryCandleStore;
import com.coinflow.domain.recovery.service.RecoveryLedger;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CandleProjectionPublisher {
    private final RecoveryLedger ledger;
    private final RecoveryCandleStore candles;
    private final LiveKlineRepository live;
    private final OhlcWindowRepository window;
    private final KlineBroadcaster broadcaster;
    private final ObjectMapper mapper;

    public void publish(List<ConsumerCheckpointService.Candle> updates) {
        // Same lock as reconciliation: delayed publication cannot overwrite a repair.
        ledger.locked(checkpoint -> {
            for (var update : updates) publish(update.symbol(), update.value());
            return null;
        });
    }

    private void publish(String symbol, ClosedKlineSnapshot candidate) {
        var s = candidate.snapshot();
        if (candles.isVerified(symbol, candidate.interval(), s.startTime())) return;
        KlineEvent event = KlineEvent.builder().symbol(symbol).interval(candidate.interval())
                .startTime(s.startTime()).closeTime(s.closeTime()).open(s.open()).high(s.high()).low(s.low())
                .close(s.close()).volume(s.volume()).trades(s.trades()).closed(s.closed()).build();
        try {
            String json = mapper.writeValueAsString(event);
            if (s.closed()) {
                window.save(symbol, candidate.interval(), new OhlcCandleSnapshot(
                        LocalDateTime.ofEpochSecond(s.startTime(), 0, ZoneOffset.UTC), s.startTime(),
                        s.open(), s.high(), s.low(), s.close(), s.volume()));
                window.trim(symbol, candidate.interval(), OhlcWindowPolicy.MAX_SIZE);
                live.deleteIfStartTimeMatches(symbol, candidate.interval(), s.startTime());
            } else live.save(event, json);
            broadcaster.broadcast(event, json);
        } catch (Exception e) { throw new IllegalStateException("Candle projection failed", e); }
    }
}
