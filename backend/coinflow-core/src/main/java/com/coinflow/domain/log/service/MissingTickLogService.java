package com.coinflow.domain.log.service;

import com.coinflow.domain.log.domain.MissingTickLog;
import com.coinflow.domain.log.repository.MissingTickLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MissingTickLogService {

    private final MissingTickLogRepository missingTickLogRepository;

    @Transactional
    public void save(MissingTickLog logEntry) {
        missingTickLogRepository.save(logEntry);
    }

    @Transactional
    public void saveAll(List<MissingTickLog> logEntries) {
        missingTickLogRepository.saveAll(logEntries);
    }
}
