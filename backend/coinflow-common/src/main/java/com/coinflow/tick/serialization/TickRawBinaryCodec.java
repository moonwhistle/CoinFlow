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

    // 프로토콜 버전 정의 (Point 1: 확장성 확보)
    public static final byte PROTOCOL_VERSION = 1;

    // Magic Numbers 제거 및 오프셋 상수화 (Clean Code)
    public static final int VERSION_SIZE = 1;
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
        // 1. 프로토콜 레벨에서의 유효성 검증 강제 (Point 2: 책임 분리 및 SRP)
        com.coinflow.tick.validation.TickValidator.validate(symbol, price, quantity, eventTime);

        try {
            byte[] symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
            int totalSize = VERSION_SIZE + SYMBOL_LEN_SIZE + symbolBytes.length + (BIG_DECIMAL_TOTAL_SIZE * 2) + EVENT_TIME_SIZE;

            ByteBuffer buffer = ByteBuffer.allocate(totalSize);

            // 1. Version
            buffer.put(PROTOCOL_VERSION);

            // 2. Symbol
            buffer.put((byte) symbolBytes.length);
            buffer.put(symbolBytes);

            // 3. Price (unscaledValue: long, scale: int)
            buffer.putLong(price.unscaledValue().longValue());
            buffer.putInt(price.scale());

            // 4. Quantity (unscaledValue: long, scale: int)
            buffer.putLong(quantity.unscaledValue().longValue());
            buffer.putInt(quantity.scale());

            // 5. EventTime
            buffer.putLong(eventTime);

            return buffer.array();
        } catch (BufferOverflowException | NullPointerException e) {
            throw new CommonException(CommonErrorCode.TICK_SERIALIZATION_FAILED);
        }
    }

    /**
     * 버전 정보 추출
     */
    public static byte extractVersion(byte[] data) {
        return data[0];
    }

    /**
     * Symbol 추출
     */
    public static String extractSymbol(byte[] data) {
        int symbolLen = data[VERSION_SIZE] & 0xFF;
        return new String(data, VERSION_SIZE + SYMBOL_LEN_SIZE, symbolLen, StandardCharsets.UTF_8);
    }

    /**
     * Price 추출
     */
    public static BigDecimal extractPrice(byte[] data) {
        int symbolLen = data[VERSION_SIZE] & 0xFF;
        int offset = VERSION_SIZE + SYMBOL_LEN_SIZE + symbolLen;

        long unscaled = readLong(data, offset);
        int scale = readInt(data, offset + BD_UNSCALED_SIZE);
        
        return BigDecimal.valueOf(unscaled, scale);
    }

    /**
     * Quantity 추출
     */
    public static BigDecimal extractQuantity(byte[] data) {
        int symbolLen = data[VERSION_SIZE] & 0xFF;
        int offset = VERSION_SIZE + SYMBOL_LEN_SIZE + symbolLen + BIG_DECIMAL_TOTAL_SIZE;

        long unscaled = readLong(data, offset);
        int scale = readInt(data, offset + BD_UNSCALED_SIZE);
        
        return BigDecimal.valueOf(unscaled, scale);
    }

    /**
     * EventTime 추출
     */
    public static long extractEventTime(byte[] data) {
        int symbolLen = data[VERSION_SIZE] & 0xFF;
        int offset = VERSION_SIZE + SYMBOL_LEN_SIZE + symbolLen + (BIG_DECIMAL_TOTAL_SIZE * 2);

        return readLong(data, offset);
    }

    private static long readLong(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 56) |
               ((long) (data[offset + 1] & 0xFF) << 48) |
               ((long) (data[offset + 2] & 0xFF) << 40) |
               ((long) (data[offset + 3] & 0xFF) << 32) |
               ((long) (data[offset + 4] & 0xFF) << 24) |
               ((long) (data[offset + 5] & 0xFF) << 16) |
               ((long) (data[offset + 6] & 0xFF) << 8) |
               ((long) (data[offset + 7] & 0xFF));
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               ((data[offset + 3] & 0xFF));
    }
}
