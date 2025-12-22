package com.coinflow.process.listener;

import com.coinflow.process.event.Ohlc1mFlushedEvent;
import com.coinflow.process.event.service.FlushedService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class Ohlc1mFlushedEventListener {

    private final List<FlushedService> flushedServices;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOhlc1mFlushed(Ohlc1mFlushedEvent event) {
        for (FlushedService service : flushedServices) {
            service.onOhlc1mFlushed(event);
        }
    }
}
