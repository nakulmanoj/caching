# Self-contained smoke test + data collection
# Run with: powershell -ExecutionPolicy Bypass -File scripts\smoke_test.ps1

param(
    [string]$Strategy  = "cache-aside",
    [string]$TtlPolicy = "static-30",
    [string]$Sigma     = "0.30",
    [int]$WarmupReqs   = 20,
    [int]$MeasureReqs  = 50
)

$REDIS   = "C:\Users\nakul\Downloads\redis"
$JAR     = "c:\Users\nakul\Downloads\caching\target\wealthwise-1.0.0-SNAPSHOT.jar"
$RESULTS = "c:\Users\nakul\Downloads\caching\results"
New-Item -ItemType Directory -Force -Path $RESULTS | Out-Null

# ── Redis ──────────────────────────────────────────────────────────────────
Write-Host "[Redis] checking..." -ForegroundColor Cyan
$ping = & "$REDIS\redis-cli.exe" ping 2>&1
if ($ping -ne "PONG") {
    Write-Host "[Redis] starting..." -ForegroundColor Yellow
    Start-Process -FilePath "$REDIS\redis-server.exe" `
        -ArgumentList "$REDIS\redis.windows.conf" -WindowStyle Hidden
    Start-Sleep -Seconds 4
}
& "$REDIS\redis-cli.exe" FLUSHALL | Out-Null
Write-Host "[Redis] ready (flushed)" -ForegroundColor Green

# ── Kill any existing app on 8080 ──────────────────────────────────────────
$existing = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -First 1
if ($existing) {
    Stop-Process -Id $existing -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Write-Host "[App] killed old process $existing" -ForegroundColor Yellow
}

# ── Start App ──────────────────────────────────────────────────────────────
$csvPath = "$RESULTS\staleness_${Strategy}_${TtlPolicy}_${Sigma}.csv"
$logPath = "$RESULTS\app_${Strategy}_${TtlPolicy}_${Sigma}.log"

Write-Host "[App] starting (strategy=$Strategy, ttl=$TtlPolicy, sigma=$Sigma)..." -ForegroundColor Cyan

$appArgs = @(
    "-jar", $JAR,
    "--spring.profiles.active=$Strategy",
    "--spring.datasource.url=jdbc:postgresql://localhost:5432/wealthwise",
    "--spring.datasource.username=wealthwise",
    "--spring.datasource.password=wealthwise",
    "--spring.data.redis.host=localhost",
    "--wealthwise.caching.ttl-policy=$TtlPolicy",
    "--wealthwise.simulator.sigma=$Sigma",
    "--wealthwise.caching.strategy=$Strategy",
    "--wealthwise.staleness.csv-output-path=$csvPath",
    "--logging.file.name=$logPath",
    "--logging.level.root=WARN",
    "--logging.level.com.wealthwise=INFO"
)

$appProc = Start-Process -FilePath "java" -ArgumentList $appArgs -WindowStyle Hidden -PassThru
Write-Host "[App] PID=$($appProc.Id)"

# ── Wait for UP ───────────────────────────────────────────────────────────
Write-Host "[App] waiting for startup..." -NoNewline
$ready = $false
for ($i = 0; $i -lt 25; $i++) {
    Start-Sleep -Seconds 3
    Write-Host "." -NoNewline
    if ($appProc.HasExited) {
        Write-Host "`n[App] CRASHED! Check $logPath"
        Get-Content $logPath -Tail 20 -ErrorAction SilentlyContinue
        exit 1
    }
    try {
        $h = Invoke-RestMethod "http://localhost:8080/actuator/health" -TimeoutSec 2 -ErrorAction Stop
        if ($h.status -eq "UP") { $ready = $true; break }
    } catch {}
}
if (-not $ready) {
    Write-Host "`n[App] TIMEOUT - not UP after 75s"
    Get-Content $logPath -Tail 30 -ErrorAction SilentlyContinue
    Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Host "`n[App] UP" -ForegroundColor Green

# ── Warm-up ───────────────────────────────────────────────────────────────
Write-Host "[Test] Warm-up ($WarmupReqs requests)..." -ForegroundColor Cyan
$users = 1..10 | ForEach-Object { "user-{0:D3}" -f $_ }
1..$WarmupReqs | ForEach-Object {
    $u = $users[($_ - 1) % 10]
    try { Invoke-RestMethod "http://localhost:8080/api/portfolio/$u/value" -TimeoutSec 5 -EA Stop | Out-Null } catch {}
}
Start-Sleep -Seconds 3   # let TTL effects settle

# ── Measurement ───────────────────────────────────────────────────────────
Write-Host "[Test] Measuring ($MeasureReqs requests)..." -ForegroundColor Cyan
$results = 1..$MeasureReqs | ForEach-Object {
    $u = $users[($_ - 1) % 10]
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-RestMethod "http://localhost:8080/api/portfolio/$u/value" -TimeoutSec 5 -EA Stop
        $sw.Stop()
        [pscustomobject]@{
            req     = $_
            user    = $u
            vCached = [math]::Round($resp.vCached, 2)
            ms      = $sw.ElapsedMilliseconds
            ok      = $true
        }
    } catch {
        $sw.Stop()
        [pscustomobject]@{ req=$_; user=$u; vCached="ERR"; ms=$sw.ElapsedMilliseconds; ok=$false }
    }
}

# ── Stats ─────────────────────────────────────────────────────────────────
$ok  = @($results | Where-Object { $_.ok })
$err = @($results | Where-Object { -not $_.ok })
Write-Host ""
Write-Host "=== RESULTS ===" -ForegroundColor Green
Write-Host "Successful: $($ok.Count)/$MeasureReqs  Errors: $($err.Count)"

if ($ok.Count -gt 0) {
    $lat = $ok.ms | Sort-Object
    $p50 = $lat[[int]($lat.Count * 0.50)]
    $p99 = $lat[[int]($lat.Count * 0.99)]
    $avg = [math]::Round(($lat | Measure-Object -Average).Average, 1)
    $min = ($lat | Measure-Object -Minimum).Minimum
    $max = ($lat | Measure-Object -Maximum).Maximum
    Write-Host "Latency - Min:${min}ms  Avg:${avg}ms  P50:${p50}ms  P99:${p99}ms  Max:${max}ms"
    Write-Host "Sample vCached: $($ok[0].vCached)"
}

$results | Select-Object -First 12 | Format-Table req, user, vCached, ms, ok -AutoSize

# ── Staleness CSV ────────────────────────────────────────────────────────
Write-Host "Waiting 6s for async staleness probes..." -ForegroundColor Gray
Start-Sleep -Seconds 6

Write-Host "`n=== STALENESS CSV ===" -ForegroundColor Green
if (Test-Path $csvPath) {
    $rows = Get-Content $csvPath
    Write-Host "$($rows.Count - 1) staleness rows written"
    $rows | Select-Object -First 6
} else {
    Write-Host "WARNING: CSV not found at $csvPath"
    Write-Host "Check app log: $logPath"
    Get-Content $logPath | Select-String "stale|csv|ERROR|WARN" | Select-Object -Last 10
}

# ── Cleanup ───────────────────────────────────────────────────────────────
Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue
Write-Host "`n[App] stopped. DONE." -ForegroundColor Green
