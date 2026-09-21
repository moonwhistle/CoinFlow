param(
    [string]$RedisHost = "localhost",
    [int]$RedisPort = 6380,
    [int]$WarmupSeconds = 15,
    [int]$DurationSeconds = 60,
    [int]$Repetitions = 3,
    [long[]]$Targets = @(1000, 5000, 10000, 0),
    [string]$RedisVersion = "unknown",
    [switch]$SkipExisting,
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$backendDirectory = Split-Path -Parent $PSScriptRoot
$repositoryDirectory = Split-Path -Parent $backendDirectory
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDirectory = Join-Path $repositoryDirectory "benchmark-results\collector-wal-$stamp"
}
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null

$rows = @()
$modeOrders = @(
    [string[]]@("DIRECT", "PIPELINE", "WAL_PIPELINE"),
    [string[]]@("PIPELINE", "WAL_PIPELINE", "DIRECT"),
    [string[]]@("WAL_PIPELINE", "DIRECT", "PIPELINE")
)
Push-Location $backendDirectory
try {
    foreach ($target in $Targets) {
        for ($run = 1; $run -le $Repetitions; $run++) {
            $modesForRun = $modeOrders[($run - 1) % $modeOrders.Count]
            foreach ($mode in $modesForRun) {
                $label = if ($target -eq 0) { "max" } else { "$target" }
                $runId = "$($mode.ToLower())-tps$label-r$run"
                $jsonPath = Join-Path $resolvedOutput "$runId.json"
                $logPath = Join-Path $resolvedOutput "$runId.console.log"
                $benchmarkArgs = "$RedisHost $RedisPort $mode $target $WarmupSeconds $DurationSeconds - $jsonPath"
                if ($SkipExisting -and (Test-Path -LiteralPath $jsonPath)) {
                    Write-Host "[collector-benchmark] skip existing $runId"
                    $result = Get-Content -Raw -LiteralPath $jsonPath | ConvertFrom-Json
                } else {
                    Write-Host "[collector-benchmark] $runId"
                    $output = & .\gradlew.bat :coinflow-load-test:collectorDeliveryBenchmark "--args=$benchmarkArgs" 2>&1
                    $output | Tee-Object -FilePath $logPath | Out-Host
                    if ($LASTEXITCODE -ne 0) {
                        throw "Benchmark failed: $runId"
                    }
                    $resultLine = $output | Where-Object { $_ -like "COLLECTOR_DELIVERY_BENCHMARK_RESULT *" } | Select-Object -Last 1
                    if ($null -eq $resultLine) {
                        throw "Result line missing: $runId"
                    }
                    $result = ($resultLine -replace '^COLLECTOR_DELIVERY_BENCHMARK_RESULT ', '') | ConvertFrom-Json
                }
                $rows += [PSCustomObject]@{
                    runId = $runId
                    mode = $result.mode
                    targetTps = $result.targetTps
                    submitted = $result.submitted
                    accepted = $result.accepted
                    confirmed = $result.confirmed
                    submitTps = $result.submitTps
                    confirmedTpsIncludingDrain = $result.confirmedTpsIncludingDrain
                    drainMillis = $result.drainMillis
                    publishCallP50Micros = $result.publishCallP50Micros
                    publishCallP95Micros = $result.publishCallP95Micros
                    publishCallP99Micros = $result.publishCallP99Micros
                    publishCallMaxMicros = $result.publishCallMaxMicros
                    endToEndP50Micros = $result.endToEndP50Micros
                    endToEndP95Micros = $result.endToEndP95Micros
                    endToEndP99Micros = $result.endToEndP99Micros
                    endToEndMaxMicros = $result.endToEndMaxMicros
                    pipelineCalls = $result.pipelineCalls
                    averageBatchSize = $result.averageBatchSize
                    pipelineRttP50Micros = $result.pipelineRttP50Micros
                    pipelineRttP95Micros = $result.pipelineRttP95Micros
                    pipelineRttP99Micros = $result.pipelineRttP99Micros
                    processCpuPctOfOneCore = $result.processCpuPctOfOneCore
                    redisCpuPctOfOneCore = $result.redisCpuPctOfOneCore
                    redisNetInputBytesPerSec = $result.redisNetInputBytesPerSec
                    redisNetOutputBytesPerSec = $result.redisNetOutputBytesPerSec
                    heapUsedMiB = $result.heapUsedMiB
                    gcCount = $result.gcCount
                    gcTimeMillis = $result.gcTimeMillis
                    walAppendBytes = $result.walAppendBytes
                    walBytesPerAccepted = $result.walBytesPerAccepted
                    walAppendP50Micros = $result.walAppendP50Micros
                    walAppendP95Micros = $result.walAppendP95Micros
                    walAppendP99Micros = $result.walAppendP99Micros
                    walFileBytes = $result.walFileBytes
                    streamLength = $result.streamLength
                }
            }
        }
    }
} finally {
    Pop-Location
}

$csvPath = Join-Path $resolvedOutput "results.csv"
$rows | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

function Get-Median([double[]]$Values) {
    $sorted = $Values | Sort-Object
    if ($sorted.Count -eq 0) { return 0 }
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

function Get-Range([double[]]$Values, [int]$Decimals = 1) {
    $minimum = ($Values | Measure-Object -Minimum).Minimum
    $maximum = ($Values | Measure-Object -Maximum).Maximum
    return "$([Math]::Round($minimum, $Decimals))-$([Math]::Round($maximum, $Decimals))"
}

function Get-ModeMedian($GroupRows, [string]$Mode, [string]$Property) {
    $values = @($GroupRows | Where-Object mode -eq $Mode | ForEach-Object { [double]($_.$Property) })
    return Get-Median $values
}

$lines = @(
    "# Collector delivery benchmark",
    "",
    "- Redis: ${RedisVersion} at ${RedisHost}:${RedisPort}",
    "- Warm-up: ${WarmupSeconds}s",
    "- Measurement: ${DurationSeconds}s",
    "- Repetitions: $Repetitions",
    "- WAL durability: FileChannel.write only; no force/fsync",
    "- Max mode: producer backpressure at 100,000 outstanding records (sustained throughput, not WAL fill rate)",
    "",
    "| Target | Mode | Confirm TPS median (range) | E2E p99 us median (range) | publish() p99 us | Avg batch | Pipeline RTT p99 us | CPU % core | WAL B/record | Drain ms |",
    "|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|"
)
foreach ($group in ($rows | Group-Object targetTps, mode | Sort-Object Name)) {
    $sample = $group.Group[0]
    $targetLabel = if ([long]$sample.targetTps -eq 0) { "max" } else { [string]$sample.targetTps }
    $confirmMedian = Get-Median $group.Group.confirmedTpsIncludingDrain
    $e2eP99Median = Get-Median $group.Group.endToEndP99Micros
    $lines += "| $targetLabel | $($sample.mode) | $([Math]::Round($confirmMedian, 1)) ($(Get-Range $group.Group.confirmedTpsIncludingDrain)) | $([Math]::Round($e2eP99Median, 1)) ($(Get-Range $group.Group.endToEndP99Micros 0)) | $([Math]::Round((Get-Median $group.Group.publishCallP99Micros), 1)) | $([Math]::Round((Get-Median $group.Group.averageBatchSize), 1)) | $([Math]::Round((Get-Median $group.Group.pipelineRttP99Micros), 1)) | $([Math]::Round((Get-Median $group.Group.processCpuPctOfOneCore), 1)) | $([Math]::Round((Get-Median $group.Group.walBytesPerAccepted), 1)) | $([Math]::Round((Get-Median $group.Group.drainMillis), 1)) |"
}
$lines += ""
$lines += "## Relative deltas"
$lines += ""
$lines += "| Target | PIPELINE vs DIRECT confirm TPS | WAL vs PIPELINE confirm TPS | PIPELINE-DIRECT E2E p99 us | WAL-PIPELINE E2E p99 us |"
$lines += "|---:|---:|---:|---:|---:|"
foreach ($targetGroup in ($rows | Group-Object targetTps | Sort-Object Name)) {
    $target = [long]$targetGroup.Group[0].targetTps
    $targetLabel = if ($target -eq 0) { "max" } else { [string]$target }
    $directTps = Get-ModeMedian $targetGroup.Group "DIRECT" "confirmedTpsIncludingDrain"
    $pipelineTps = Get-ModeMedian $targetGroup.Group "PIPELINE" "confirmedTpsIncludingDrain"
    $walTps = Get-ModeMedian $targetGroup.Group "WAL_PIPELINE" "confirmedTpsIncludingDrain"
    $directP99 = Get-ModeMedian $targetGroup.Group "DIRECT" "endToEndP99Micros"
    $pipelineP99 = Get-ModeMedian $targetGroup.Group "PIPELINE" "endToEndP99Micros"
    $walP99 = Get-ModeMedian $targetGroup.Group "WAL_PIPELINE" "endToEndP99Micros"
    $lines += "| $targetLabel | $([Math]::Round((($pipelineTps / $directTps) - 1) * 100, 1))% | $([Math]::Round((($walTps / $pipelineTps) - 1) * 100, 1))% | $([Math]::Round($pipelineP99 - $directP99, 0)) | $([Math]::Round($walP99 - $pipelineP99, 0)) |"
}
$lines += ""
$lines += 'Raw data: `results.csv` and per-run JSON files. `PIPELINE - DIRECT` isolates batching; `WAL_PIPELINE - PIPELINE` isolates synchronous WAL append plus checkpoint cost. E2E starts immediately before publish/enqueue/WAL append and ends only after all Redis XADD replies are confirmed.'
$lines | Set-Content -Path (Join-Path $resolvedOutput "README.md") -Encoding UTF8

Write-Host "[collector-benchmark] Results: $resolvedOutput"
