package com.coinflow.process.listener;

import com.coinflow.process.event.Ohlc1mFlushedEvent;
import com.coinflow.process.event.service.FlushedService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class Ohlc1mFlushedEventListener {

    private final List<FlushedService> flushedServices;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOhlc1mFlushed(Ohlc1mFlushedEvent event) {
        for (FlushedService service : flushedServices) {
            try {
                service.onOhlc1mFlushed(event);
            } catch (Exception e) {
                log.error(
                        "Failed to handle Ohlc1mFlushedEvent in service={}. symbolId={}, bucketStart1m={}",
                        service.getClass().getSimpleName(),
                        event.symbolId(),
                        event.bucketStart1m(),
                        e
                );
                // 다른 서비스들은 계속 실행
            }
        }
    }
}
