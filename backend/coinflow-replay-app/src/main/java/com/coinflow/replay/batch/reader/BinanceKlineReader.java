package com.coinflow.replay.batch.reader;

import com.coinflow.replay.client.BinanceKlineClient;
import com.coinflow.replay.client.dto.BinanceKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemReader;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class BinanceKlineReader implements ItemReader<BinanceKline> {
    private static final Logger log = LoggerFactory.getLogger(BinanceKlineReader.class);

    private final BinanceKlineClient binanceKlineClient;

    private final String symbol;
    private final String interval;
    private final long endTime;
    private final int limit = 500;

    private long currentStartTime;
    private final Queue<BinanceKline> klineBuffer = new LinkedList<>();
    private boolean isFinished = false;

    public BinanceKlineReader(BinanceKlineClient binanceKlineClient, String symbol, String interval, long startTime,
            long endTime) {
        this.binanceKlineClient = binanceKlineClient;
        this.symbol = symbol != null ? symbol : "BTCUSDT";
        this.interval = interval != null ? interval : "1m";
        this.currentStartTime = startTime;
        this.endTime = endTime;
        log.info("Initialized BinanceKlineReader for symbol={}, interval={}, start={}, end={}",
                this.symbol, this.interval, this.currentStartTime, this.endTime);
    }

    @Override
    public BinanceKline read() {
        // 이미 가져온 버퍼에 데이터가 있다면 하나씩 빼서 넘겨줍니다.
        if (!klineBuffer.isEmpty()) {
            return klineBuffer.poll();
        }

        // 버퍼가 비었고 더 이상 가져올 데이터가 없다면 null을 반환하여 배치를 종료시킵니다.
        if (isFinished || currentStartTime > endTime) {
            return null;
        }

        log.debug("Fetching next batch of klines starting from {}, end {}", currentStartTime, endTime);
        List<BinanceKline> klines = binanceKlineClient.fetchKlines(symbol, interval, currentStartTime, endTime, limit);

        // API 응답이 없으면 완전히 끝난 것입니다.
        if (klines.isEmpty()) {
            isFinished = true;
            return null;
        }

        // 받은 데이터를 버퍼에 채워 넣습니다.
        klineBuffer.addAll(klines);

        // 페이지네이션(Cursor) 처리:
        // 바이낸스 API는 데이터 정렬을 보장하므로, 받은 리스트의 맨 마지막 캔들 openTime을 찾고 1ms를 더해서 다음 시작점으로
        // 잡습니다.
        long lastOpenTime = klines.get(klines.size() - 1).openTime();
        currentStartTime = lastOpenTime + 1;

        // 받은 개수가 Max Limit(500개)보다 적다면, 이는 우리가 요청한 기간 내의 데이터를 모두 소진했다는 뜻입니다.
        if (klines.size() < limit) {
            isFinished = true;
        }

        // 새롭게 채워진 버퍼에서 প্রথম 캔들을 꺼내어 넘겨줍니다.
        return klineBuffer.poll();
    }
}
