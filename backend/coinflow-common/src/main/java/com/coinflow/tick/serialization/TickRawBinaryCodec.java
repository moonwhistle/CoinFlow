package com.coinflow.tick.serialization;

import com.coinflow.common.exception.CommonErrorCode;
import com.coinflow.common.exception.CommonException;
import java.math.BigDecimal;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * TickRawEvent 데이터의 바이너리 직렬화/역직렬화를 담당하는 코덱 클래스입니다.
 */
public final class TickRawBinaryCodec {

    // Magic Numbers 및 Offset 정의
    private static final int SYMBOL_LEN_SIZE = 1;
    private static final int BD_UNSCALED_SIZE = 8;
    private static final int BD_SCALE_SIZE = 4;
    private static final int BIG_DECIMAL_TOTAL_SIZE = BD_UNSCALED_SIZE + BD_SCALE_SIZE; // 12 bytes
    private static final int EVENT_TIME_SIZE = 8;

    private TickRawBinaryCodec() {}

    /**
     * 주어지는 개별 필드값들을 바이너리 패킷으로 인코딩합니다.
     */
    public static byte[] encode(String symbol, BigDecimal price, BigDecimal quantity, long eventTime) {
        try {
            byte[] symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
            int totalSize = SYMBOL_LEN_SIZE + symbolBytes.length + (BIG_DECIMAL_TOTAL_SIZE * 2) + EVENT_TIME_SIZE;

            ByteBuffer buffer = ByteBuffer.allocate(totalSize);

            // 1. Symbol
            buffer.put((byte) symbolBytes.length);
            buffer.put(symbolBytes);

            // 2. Price (unscaledValue: long, scale: int)
            buffer.putLong(price.unscaledValue().longValue());
            buffer.putInt(price.scale());

            // 3. Quantity (unscaledValue: long, scale: int)
            buffer.putLong(quantity.unscaledValue().longValue());
            buffer.putInt(quantity.scale());

            // 4. EventTime
            buffer.putLong(eventTime);

            return buffer.array();
        } catch (BufferOverflowException | NullPointerException e) {
            throw new CommonException(CommonErrorCode.TICK_SERIALIZATION_FAILED);
        }
    }

    /**
     * Symbol 추출
     */
    public static String extractSymbol(byte[] data) {
        int symbolLen = data[0] & 0xFF;
        return new String(data, SYMBOL_LEN_SIZE, symbolLen, StandardCharsets.UTF_8);
    }

    /**
     * Price 추출
     */
    public static BigDecimal extractPrice(byte[] data) {
        int symbolLen = data[0] & 0xFF;
        int offset = SYMBOL_LEN_SIZE + symbolLen;

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, BIG_DECIMAL_TOTAL_SIZE);
        return BigDecimal.valueOf(buffer.getLong(), buffer.getInt());
    }

    /**
     * Quantity 추출
     */
    public static BigDecimal extractQuantity(byte[] data) {
        int symbolLen = data[0] & 0xFF;
        int offset = SYMBOL_LEN_SIZE + symbolLen + BIG_DECIMAL_TOTAL_SIZE;

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, BIG_DECIMAL_TOTAL_SIZE);
        return BigDecimal.valueOf(buffer.getLong(), buffer.getInt());
    }

    /**
     * EventTime 추출
     */
    public static long extractEventTime(byte[] data) {
        int symbolLen = data[0] & 0xFF;
        int offset = SYMBOL_LEN_SIZE + symbolLen + (BIG_DECIMAL_TOTAL_SIZE * 2);

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, EVENT_TIME_SIZE);
        return buffer.getLong();
    }
}
