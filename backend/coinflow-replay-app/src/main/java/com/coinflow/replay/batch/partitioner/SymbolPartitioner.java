package com.coinflow.replay.batch.partitioner;

import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class SymbolPartitioner implements Partitioner {

    private final List<String> symbols;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new HashMap<>();

        for (String symbol : symbols) {
            ExecutionContext context = new ExecutionContext();
            context.putString(ReconciliationBatchConstants.PARAM_SYMBOL, symbol.toLowerCase());
            partitions.put("partition_" + symbol, context);
        }

        return partitions;
    }
}
