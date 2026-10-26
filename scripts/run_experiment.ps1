# =============================================================================
# WealthWise Full Experiment Runner (PowerShell)
# Runs all 45 configurations: 3 strategies x 5 TTL policies x 3 volatility
# =============================================================================

param(
    [switch]$Smoke,    # Run only 1 config (quick sanity check)
    [switch]$All,      # Run all 45 configs
    [string]$Single    # Run single: e.g. "cache-aside,static-30,0.30"
)

$MVN   = "C:\Users\nakul\apache-maven-3.9.6\bin\mvn.cmd"
$JAVA  = "java"
$JAR   = "c:\Users\nakul\Downloads\caching\target\wealthwise-1.0.0-SNAPSHOT.jar"
$REDIS = "C:\Users\nakul\Downloads\redis"
$RESULTS = "c:\Users\nakul\Downloads\caching\results"
$PG_PSQL = "C:\Program Files\PostgreSQL\18\bin\psql.exe"

# Experiment matrix
$STRATEGIES  = @("cache-aside", "write-through", "swr")
$TTL_POLICIES = @("static-5", "static-30", "static-60", "dynamic", "event")
$SIGMAS      = @("0.05", "0.30", "0.80")   # LOW, MEDIUM, HIGH
$SIGMA_NAMES = @{ "0.05"="LOW"; "0.30"="MEDIUM"; "0.80"="HIGH" }

# Requests per run (warm-up + measurement)
$WARMUP_REQS   = 50
$MEASURE_REQS  = 200
$REQUEST_DELAY_MS = 100  # ms between requests (10 req/s)

# -----------------------------------------------------------------------
function Start-Redis {
    $ping = & "$REDIS\redis-cli.exe" ping 2>&1
    if ($ping -ne "PONG") {
        Write-Host "  [Redis] Starting..."
        Start-Process -FilePath "$REDIS\redis-server.exe" `
            -ArgumentList "$REDIS\redis.windows.conf" -WindowStyle Hidden
        Start-Sleep -Seconds 3
    }
    & "$REDIS\redis-cli.exe" FLUSHALL | Out-Null
    Write-Host "  [Redis] Ready"
}

function Stop-App {
    $conn = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
    if ($conn) {
        $pids = $conn | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -gt 4 }
        foreach ($p in $pids) {
            Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
            Write-Host "  [App] Stopped (PID $p)"
        }
        Start-Sleep -Seconds 2
    }
}

function Start-App($strategy, $ttlPolicy, $sigma) {
    Stop-App
    & "$REDIS\redis-cli.exe" FLUSHALL | Out-Null   # flush between runs

    $csvPath = Join-Path $RESULTS "staleness_${strategy}_${ttlPolicy}_${sigma}.csv"
    $logPath = Join-Path $RESULTS "app_${strategy}_${ttlPolicy}_${sigma}.log"

    $args = @(
        "-jar", $JAR,
        "--spring.profiles.active=$strategy",
        "--spring.datasource.url=jdbc:postgresql://localhost:5432/wealthwise",
        "--spring.datasource.username=wealthwise",
        "--spring.datasource.password=wealthwise",
        "--spring.data.redis.host=localhost",
        "--wealthwise.caching.ttl-policy=$ttlPolicy",
        "--wealthwise.simulator.sigma=$sigma",
        "--wealthwise.caching.strategy=$strategy",
        "--wealthwise.staleness.csv-output-path=$csvPath",
        "--logging.file.name=$logPath",
        "--logging.level.root=WARN",
        "--logging.level.com.wealthwise=INFO"
    )

    $proc = Start-Process -FilePath $JAVA -ArgumentList $args `
        -WindowStyle Hidden -PassThru
    Write-Host "  [App] Started PID=$($proc.Id)"

    # Wait for UP
    $ready = $false
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Seconds 3
        if ($proc.HasExited) {
            Write-Host "  [App] Process exited prematurely! Exit code: $($proc.ExitCode)"
            break
        }
        try {
            $h = Invoke-RestMethod "http://localhost:8080/actuator/health" -TimeoutSec 3 -ErrorAction Stop
            if ($h.status -eq "UP") { $ready = $true; break }
        } catch {}
    }
    if (-not $ready) { throw "App did not start for $strategy/$ttlPolicy/$sigma" }
    Write-Host "  [App] UP (sigma=$sigma, ttl=$ttlPolicy)"
    return $proc
}

function Invoke-Requests($count, $label) {
    $users = 1..10 | ForEach-Object { "user-{0:D3}" -f $_ }
    $latencies = @()
    $errors = 0
    $firstError = $null

    for ($i = 1; $i -le $count; $i++) {
        $user = $users[($i - 1) % 10]
        $sw = [System.Diagnostics.Stopwatch]::New()
        $sw.Start()
        try {
            $null = Invoke-RestMethod "http://localhost:8080/api/portfolio/$user/value" -TimeoutSec 10 -ErrorAction Stop
            $sw.Stop()
            $latencies += $sw.ElapsedMilliseconds
        } catch {
            $sw.Stop()
            $errors++
            if (-not $firstError) {
                $firstError = $_.Exception.Message
                Write-Host "    [Invoke-Requests ERROR] $firstError" -ForegroundColor Yellow
            }
        }
        if ($REQUEST_DELAY_MS -gt 0) { Start-Sleep -Milliseconds $REQUEST_DELAY_MS }
    }
    $p50 = if ($latencies.Count -gt 0) { [math]::Round(($latencies | Sort-Object)[[int]($latencies.Count*0.5)], 1) } else { 0 }
    $p99 = if ($latencies.Count -gt 0) { [math]::Round(($latencies | Sort-Object)[[int]($latencies.Count*0.99)], 1) } else { 0 }
    Write-Host "  [$label] $count reqs, errors=$errors, P50=${p50}ms, P99=${p99}ms"
    return @{ latencies=$latencies; errors=$errors }
}

function Run-Config {
    param(
        [string]$strategy,
        [string]$ttlPolicy,
        [string]$sigma
    )
    $regime = switch ($sigma) {
        "0.05" { "low" }
        "0.30" { "medium" }
        "0.80" { "high" }
        default { "medium" }
    }
    Write-Host "`n=== [$strategy / $ttlPolicy / $($regime.ToUpper()) (sigma=$sigma)] ==="

    $appProc = Start-App $strategy $ttlPolicy $sigma

    # Warm-up (not measured)
    Write-Host "  Warming up ($WARMUP_REQS reqs)..."
    Invoke-Requests $WARMUP_REQS "WARMUP" | Out-Null
    Start-Sleep -Seconds 5   # let TTL effects settle

    # Measurement
    Write-Host "  Measuring ($MEASURE_REQS reqs)..."
    $stats = Invoke-Requests $MEASURE_REQS "MEASURE"
    Start-Sleep -Seconds 3   # let async staleness probes finish

    # Collect Redis INFO snapshot
    $redisDir = Join-Path $RESULTS "redis"
    if (-not (Test-Path $redisDir)) { New-Item -ItemType Directory -Force -Path $redisDir | Out-Null }
    $redisInfo = & "$REDIS\redis-cli.exe" INFO stats 2>&1
    $redisFile = Join-Path $redisDir "${strategy}__${ttlPolicy}__${regime}.txt"
    $redisInfo | Out-File -FilePath $redisFile -Encoding ascii
    $hits   = ($redisInfo | Select-String "keyspace_hits:(\d+)").Matches.Groups[1].Value
    $misses = ($redisInfo | Select-String "keyspace_misses:(\d+)").Matches.Groups[1].Value

    # Export JMeter format latencies for collect_metrics.py
    $jmeterDir = Join-Path $RESULTS "jmeter"
    if (-not (Test-Path $jmeterDir)) { New-Item -ItemType Directory -Force -Path $jmeterDir | Out-Null }
    $jmeterCsv = Join-Path $jmeterDir "${strategy}__${ttlPolicy}__${regime}_measure.csv"
    $jmeterLines = @("timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect")
    $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    foreach ($lat in $stats.latencies) {
        $jmeterLines += "$nowMs,$lat,GET_Portfolio,200,OK,Thread-1,text,true,,200,100,1,1,http://localhost:8080/api/portfolio,${lat},0,0"
    }
    $jmeterLines | Out-File -FilePath $jmeterCsv -Encoding ascii

    # Append to master staleness.csv
    $csvPath = Join-Path $RESULTS "staleness_${strategy}_${ttlPolicy}_${sigma}.csv"
    $masterCsv = Join-Path $RESULTS "staleness.csv"
    if (Test-Path $csvPath) {
        $lines = Get-Content $csvPath
        if (-not (Test-Path $masterCsv)) {
            $lines | Out-File -FilePath $masterCsv -Encoding ascii
        } else {
            if ($lines.Count -gt 1) {
                $lines[1..($lines.Count - 1)] | Out-File -FilePath $masterCsv -Append -Encoding ascii
            }
        }
    }
    $rowCount = if (Test-Path $csvPath) { (Get-Content $csvPath).Count - 1 } else { 0 }
    if ($appProc -and -not $appProc.HasExited) {
        Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue
    }
    Stop-App
}

# -----------------------------------------------------------------------
# MAIN
New-Item -ItemType Directory -Force -Path $RESULTS | Out-Null
Start-Redis

if ($Smoke) {
    Write-Host "=== SMOKE TEST (1 config) ==="
    Run-Config "cache-aside" "static-30" "0.30"

} elseif ($Single) {
    $parts = $Single -split ","
    Run-Config $parts[0] $parts[1] $parts[2]

} else {
    # Full 45-run experiment
    $total = $STRATEGIES.Count * $TTL_POLICIES.Count * $SIGMAS.Count
    $run = 0
    $startTime = Get-Date

    foreach ($strategy in $STRATEGIES) {
        foreach ($ttlPolicy in $TTL_POLICIES) {
            foreach ($sigma in $SIGMAS) {
                $run++
                $elapsed = (Get-Date) - $startTime
                $eta = if ($run -gt 1) {
                    [math]::Round($elapsed.TotalMinutes / ($run - 1) * ($total - $run + 1), 1)
                } else { "?" }
                Write-Host "`n[$run/$total] ETA: ~${eta}min remaining"
                Run-Config $strategy $ttlPolicy $sigma
            }
        }
    }

    Write-Host "`n=== ALL $total RUNS COMPLETE ==="
    Write-Host "Results in: $RESULTS"
    Write-Host "Next step: python scripts\collect_metrics.py --results-dir $RESULTS"
}
