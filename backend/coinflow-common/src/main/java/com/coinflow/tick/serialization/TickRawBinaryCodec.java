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
    public static final byte LEGACY_PROTOCOL_VERSION = 1;
    public static final byte PROTOCOL_VERSION = 2;

    // Magic Numbers 제거 및 오프셋 상수화 (Clean Code)
    public static final int VERSION_SIZE = 1;
    private static final int SYMBOL_LEN_SIZE = 1;
    private static final int BD_UNSCALED_SIZE = 8;
    private static final int BD_SCALE_SIZE = 4;
    private static final int BIG_DECIMAL_TOTAL_SIZE = BD_UNSCALED_SIZE + BD_SCALE_SIZE; // 12 bytes
    private static final int EVENT_TIME_SIZE = 8;
    private static final int TRADE_ID_SIZE = 8;

    private TickRawBinaryCodec() {}

    /**
     * 주어지는 개별 필드값들을 바이너리 패킷으로 인코딩합니다.
     */
    public static byte[] encode(String symbol, BigDecimal price, BigDecimal quantity, long eventTime) {
        return encodeV1(symbol, price, quantity, eventTime);
    }

    /**
     * Protocol v2 encoder. Adds the exchange-provided trade id so retries can be
     * de-duplicated independently of the Redis Stream record id.
     */
    public static byte[] encode(String symbol, long tradeId, BigDecimal price, BigDecimal quantity, long eventTime) {
        if (tradeId < 0) {
            throw new CommonException(CommonErrorCode.TICK_SERIALIZATION_FAILED);
        }
        com.coinflow.tick.validation.TickValidator.validate(symbol, price, quantity, eventTime);

        try {
            byte[] symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
            validateSymbolLength(symbolBytes);
            int totalSize = VERSION_SIZE + SYMBOL_LEN_SIZE + symbolBytes.length + TRADE_ID_SIZE
                    + (BIG_DECIMAL_TOTAL_SIZE * 2) + EVENT_TIME_SIZE;

            ByteBuffer buffer = ByteBuffer.allocate(totalSize);
            buffer.put(PROTOCOL_VERSION);
            buffer.put((byte) symbolBytes.length);
            buffer.put(symbolBytes);
            buffer.putLong(tradeId);
            putBigDecimal(buffer, price);
            putBigDecimal(buffer, quantity);
            buffer.putLong(eventTime);
            return buffer.array();
        } catch (BufferOverflowException | NullPointerException | ArithmeticException e) {
            throw new CommonException(CommonErrorCode.TICK_SERIALIZATION_FAILED);
        }
    }

    private static byte[] encodeV1(String symbol, BigDecimal price, BigDecimal quantity, long eventTime) {
        // 1. 프로토콜 레벨에서의 유효성 검증 강제 (Point 2: 책임 분리 및 SRP)
        com.coinflow.tick.validation.TickValidator.validate(symbol, price, quantity, eventTime);

        try {
            byte[] symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
            validateSymbolLength(symbolBytes);
            int totalSize = VERSION_SIZE + SYMBOL_LEN_SIZE + symbolBytes.length + (BIG_DECIMAL_TOTAL_SIZE * 2) + EVENT_TIME_SIZE;

            ByteBuffer buffer = ByteBuffer.allocate(totalSize);

            // 1. Version
            buffer.put(LEGACY_PROTOCOL_VERSION);

            // 2. Symbol
            buffer.put((byte) symbolBytes.length);
            buffer.put(symbolBytes);

            // 3. Price (unscaledValue: long, scale: int)
            putBigDecimal(buffer, price);

            // 4. Quantity (unscaledValue: long, scale: int)
            putBigDecimal(buffer, quantity);

            // 5. EventTime
            buffer.putLong(eventTime);

            return buffer.array();
        } catch (BufferOverflowException | NullPointerException | ArithmeticException e) {
            throw new CommonException(CommonErrorCode.TICK_SERIALIZATION_FAILED);
        }
    }

    public static boolean isSupportedVersion(byte version) {
        return version == LEGACY_PROTOCOL_VERSION || version == PROTOCOL_VERSION;
    }

    public static long extractTradeId(byte[] data) {
        requireVersion(data, PROTOCOL_VERSION);
        int symbolLen = symbolLength(data);
        return readLong(data, VERSION_SIZE + SYMBOL_LEN_SIZE + symbolLen);
    }

    /**
     * 버전 정보 추출
     */
    public static byte extractVersion(byte[] data) {
        requireAvailable(data, 0, VERSION_SIZE);
        return data[0];
    }

    /**
     * Symbol 추출
     */
    public static String extractSymbol(byte[] data) {
        int symbolLen = symbolLength(data);
        return new String(data, VERSION_SIZE + SYMBOL_LEN_SIZE, symbolLen, StandardCharsets.UTF_8);
    }

    /**
     * Price 추출
     */
    public static BigDecimal extractPrice(byte[] data) {
        int symbolLen = symbolLength(data);
        int offset = fieldsOffset(data, symbolLen);

        long unscaled = readLong(data, offset);
        int scale = readInt(data, offset + BD_UNSCALED_SIZE);
        
        return BigDecimal.valueOf(unscaled, scale);
    }

    /**
     * Quantity 추출
     */
    public static BigDecimal extractQuantity(byte[] data) {
        int symbolLen = symbolLength(data);
        int offset = fieldsOffset(data, symbolLen) + BIG_DECIMAL_TOTAL_SIZE;

        long unscaled = readLong(data, offset);
        int scale = readInt(data, offset + BD_UNSCALED_SIZE);
        
        return BigDecimal.valueOf(unscaled, scale);
    }

    /**
     * EventTime 추출
     */
    public static long extractEventTime(byte[] data) {
        int symbolLen = symbolLength(data);
        int offset = fieldsOffset(data, symbolLen) + (BIG_DECIMAL_TOTAL_SIZE * 2);

        return readLong(data, offset);
    }

    private static long readLong(byte[] data, int offset) {
        requireAvailable(data, offset, Long.BYTES);
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
        requireAvailable(data, offset, Integer.BYTES);
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               ((data[offset + 3] & 0xFF));
    }

    private static int fieldsOffset(byte[] data, int symbolLen) {
        byte version = extractVersion(data);
        if (!isSupportedVersion(version)) {
            throw new IllegalArgumentException("Unsupported tick protocol version: " + version);
        }
        return VERSION_SIZE + SYMBOL_LEN_SIZE + symbolLen
                + (version == PROTOCOL_VERSION ? TRADE_ID_SIZE : 0);
    }

    private static int symbolLength(byte[] data) {
        requireAvailable(data, 0, VERSION_SIZE + SYMBOL_LEN_SIZE);
        int symbolLen = data[VERSION_SIZE] & 0xFF;
        requireAvailable(data, VERSION_SIZE + SYMBOL_LEN_SIZE, symbolLen);
        return symbolLen;
    }

    private static void putBigDecimal(ByteBuffer buffer, BigDecimal value) {
        buffer.putLong(value.unscaledValue().longValueExact());
        buffer.putInt(value.scale());
    }

    private static void validateSymbolLength(byte[] symbolBytes) {
        if (symbolBytes.length > 255) {
            throw new CommonException(CommonErrorCode.TICK_SERIALIZATION_FAILED);
        }
    }

    private static void requireVersion(byte[] data, byte expected) {
        byte actual = extractVersion(data);
        if (actual != expected) {
            throw new IllegalArgumentException("Expected protocol version " + expected + " but was " + actual);
        }
    }

    private static void requireAvailable(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("Malformed tick payload");
        }
    }
}
