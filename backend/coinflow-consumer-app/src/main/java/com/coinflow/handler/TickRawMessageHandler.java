package com.coinflow.handler;

import static com.coinflow.publish.stream.RedisStreamTickPublisher.RAW_PAYLOAD_FIELD;
import com.coinflow.aggregation.service.TickProcessService;
import com.coinflow.recovery.service.FailedRecordService;
import com.coinflow.tick.serialization.TickRawBinaryCodec;
import com.coinflow.tick.validation.TickValidator;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TickRawMessageHandler {
    private final TickProcessService tickProcessService;
    private final FailedRecordService failures;

    public boolean handle(Map<String, byte[]> value, String stream, String group, RecordId id) {
        byte[] raw = value.get(RAW_PAYLOAD_FIELD);
        String symbol = null;
        Long eventTime = null;
        BigDecimal price;
        BigDecimal quantity;
        Long tradeId;
        try {
            if (raw == null) throw new IllegalArgumentException("Missing raw payload");
            byte version = TickRawBinaryCodec.extractVersion(raw);
            if (!TickRawBinaryCodec.isSupportedVersion(version)) throw new IllegalArgumentException("Unsupported binary version");
            symbol = TickRawBinaryCodec.extractSymbol(raw);
            eventTime = TickRawBinaryCodec.extractEventTime(raw);
            price = TickRawBinaryCodec.extractPrice(raw);
            quantity = TickRawBinaryCodec.extractQuantity(raw);
            tradeId = version == TickRawBinaryCodec.PROTOCOL_VERSION ? TickRawBinaryCodec.extractTradeId(raw) : null;
            TickValidator.validate(symbol, price, quantity, eventTime);
        } catch (RuntimeException e) {
            failures.record(stream, group, id.getValue(), raw, symbol, eventTime, e);
            return false;
        }
        try {
            tickProcessService.process(symbol, tradeId, price, quantity, eventTime, stream, group, id);
            return true;
        } catch (TickProcessService.InvalidTickException e) {
            failures.record(stream, group, id.getValue(), raw, symbol, eventTime, e);
            return false;
        }
        // Infrastructure/checkpoint errors propagate to the subscription error handler: fail closed.
    }
}
