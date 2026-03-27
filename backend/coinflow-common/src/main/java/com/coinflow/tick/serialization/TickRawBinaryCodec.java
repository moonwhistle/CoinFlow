package com.coinflow.tick.serialization;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * TickRawEvent 데이터의 바이너리 직렬화/역직렬화를 담당하는 코덱 클래스입니다.
 * 객체 생성을 최소화하고 고성능 처리를 위해 ByteBuffer 및 기본형 데이터를 직접 다룹니다.
 *
 * [Binary Format]
 * - Symbol: [1 byte (length)] + [N bytes (UTF-8)]
 * - Price: [8 bytes (unscaled value long)] + [4 bytes (scale int)]
 * - Quantity: [8 bytes (unscaled value long)] + [4 bytes (scale int)]
 * - EventTime: [8 bytes (epoch millis long)]
 */
public final class TickRawBinaryCodec {

    private TickRawBinaryCodec() {}

    /**
     * 주어지는 개별 필드값들을 바이너리 패킷으로 인코딩합니다.
     * Collector 모듈에서 Zero-POJO 방식을 구현할 때 사용합니다.
     */
    public static byte[] encode(String symbol, BigDecimal price, BigDecimal quantity, long eventTime) {
        byte[] symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
        int totalSize = 1 + symbolBytes.length + 8 + 4 + 8 + 4 + 8;
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        // Symbol packing
        buffer.put((byte) symbolBytes.length);
        buffer.put(symbolBytes);
        
        // Price packing
        buffer.putLong(price.unscaledValue().longValue());
        buffer.putInt(price.scale());
        
        // Quantity packing
        buffer.putLong(quantity.unscaledValue().longValue());
        buffer.putInt(quantity.scale());
        
        // EventTime packing
        buffer.putLong(eventTime);
        
        return buffer.array();
    }

    /**
     * 바이너리 데이터에서 Symbol 값만 즉시 추출합니다.
     */
    public static String extractSymbol(byte[] data) {
        int symbolLen = data[0] & 0xFF;
        return new String(data, 1, symbolLen, StandardCharsets.UTF_8);
    }

    /**
     * 바이너리 데이터에서 Price 값을 BigDecimal로 복구하여 추출합니다.
     */
    public static BigDecimal extractPrice(byte[] data) {
        int symbolLen = data[0] & 0xFF;
        int offset = 1 + symbolLen;
        
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 12);
        long unscaled = buffer.getLong();
        int scale = buffer.getInt();
        
        return BigDecimal.valueOf(unscaled, scale);
    }

    /**
     * 바이너리 데이터에서 Quantity 값을 BigDecimal로 복구하여 추출합니다.
     */
    public static BigDecimal extractQuantity(byte[] data) {
        int symbolLen = data[0] & 0xFF;
        int offset = 1 + symbolLen + 12; // Symbol + Price 오프셋
        
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 12);
        long unscaled = buffer.getLong();
        int scale = buffer.getInt();
        
        return BigDecimal.valueOf(unscaled, scale);
    }

    /**
     * 바이너리 데이터에서 EventTime 값을 추출합니다.
     */
    public static long extractEventTime(byte[] data) {
        int symbolLen = data[0] & 0xFF;
        int offset = 1 + symbolLen + 12 + 12; // Symbol + Price + Quantity 오프셋
        
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, 8);
        return buffer.getLong();
    }
}
