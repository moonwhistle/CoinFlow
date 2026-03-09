package com.coinflow.replay.batch.reader;

import com.coinflow.replay.client.BinanceKlineClient;
import com.coinflow.replay.client.dto.BinanceKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.coinflow.replay.common.exception.ReplayErrorCode;
import com.coinflow.replay.common.exception.ReplayException;
import org.springframework.batch.item.ItemReader;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class BinanceKlineReader implements ItemReader<BinanceKline> {
    private static final Logger log = LoggerFactory.getLogger(BinanceKlineReader.class);
    private static final int MAX_LIMIT = 500;

    private final BinanceKlineClient binanceKlineClient;
    private final String symbol;
    private final String interval;
    private final long endTime;

    private long currentStartTime;
    private final Queue<BinanceKline> klineBuffer = new LinkedList<>();
    private boolean isFinished = false;

    public BinanceKlineReader(BinanceKlineClient binanceKlineClient, String symbol, String interval, long startTime,
            long endTime) {
        if (binanceKlineClient == null) {
            throw new ReplayException(ReplayErrorCode.INVALID_BATCH_PARAMETER);
        }
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new ReplayException(ReplayErrorCode.INVALID_BATCH_PARAMETER);
        }
        if (interval == null || interval.trim().isEmpty()) {
            throw new ReplayException(ReplayErrorCode.INVALID_BATCH_PARAMETER);
        }
        if (startTime > endTime) {
            throw new ReplayException(ReplayErrorCode.INVALID_BATCH_PARAMETER);
        }

        this.binanceKlineClient = binanceKlineClient;
        this.symbol = symbol;
        this.interval = interval;
        this.currentStartTime = startTime;
        this.endTime = endTime;
        log.info("Initialized BinanceKlineReader for symbol={}, interval={}, start={}, end={}",
                this.symbol, this.interval, this.currentStartTime, this.endTime);
    }

    @Override
    public BinanceKline read() {
        if (!klineBuffer.isEmpty()) {
            return klineBuffer.poll();
        }

        if (isFinished || currentStartTime > endTime) {
            return null;
        }

        fetchAndBufferKlines();

        return klineBuffer.poll();
    }

    private void fetchAndBufferKlines() {
        log.debug("Fetching next batch of klines starting from {}, end {}", currentStartTime, endTime);
        List<BinanceKline> klines = binanceKlineClient.fetchKlines(symbol, interval, currentStartTime, endTime,
                MAX_LIMIT);

        if (klines.isEmpty()) {
            isFinished = true;
            return;
        }

        klineBuffer.addAll(klines);

        long lastOpenTime = klines.get(klines.size() - 1).openTime();
        currentStartTime = lastOpenTime + 1;

        if (klines.size() < MAX_LIMIT) {
            isFinished = true;
        }
    }
}
