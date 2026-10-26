@echo off
setlocal

:: Kill any existing instance
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 "') do (
    taskkill /F /PID %%a 2>nul
)
timeout /t 2 /nobreak >nul

:: Start Redis if not running
C:\Users\nakul\Downloads\redis\redis-cli.exe ping 2>nul | findstr /i "PONG" >nul
if errorlevel 1 (
    echo Starting Redis...
    start "" /b "C:\Users\nakul\Downloads\redis\redis-server.exe" "C:\Users\nakul\Downloads\redis\redis.windows.conf"
    timeout /t 3 /nobreak >nul
)
C:\Users\nakul\Downloads\redis\redis-cli.exe FLUSHALL >nul
echo Redis: ready

:: Create results dir
if not exist "c:\Users\nakul\Downloads\caching\results" mkdir "c:\Users\nakul\Downloads\caching\results"

:: Start app — truly detached with output to file
set JAVA_OPTS=-jar "c:\Users\nakul\Downloads\caching\target\wealthwise-1.0.0-SNAPSHOT.jar"
set APP_ARGS=--spring.profiles.active=%1
set APP_ARGS=%APP_ARGS% --spring.datasource.url=jdbc:postgresql://localhost:5432/wealthwise
set APP_ARGS=%APP_ARGS% --spring.datasource.username=wealthwise
set APP_ARGS=%APP_ARGS% --spring.datasource.password=wealthwise
set APP_ARGS=%APP_ARGS% --spring.data.redis.host=localhost
set APP_ARGS=%APP_ARGS% --wealthwise.caching.ttl-policy=%2
set APP_ARGS=%APP_ARGS% --wealthwise.simulator.sigma=%3
set APP_ARGS=%APP_ARGS% --wealthwise.staleness.csv-output-path=c:/Users/nakul/Downloads/caching/results/staleness_%1_%2_%3.csv
set APP_ARGS=%APP_ARGS% --wealthwise.caching.strategy=%1
set APP_ARGS=%APP_ARGS% --logging.file.name=c:/Users/nakul/Downloads/caching/results/app_%1_%2_%3.log

echo Starting app: profile=%1 ttl=%2 sigma=%3
start "" /b java %JAVA_OPTS% %APP_ARGS%

:: Wait for startup (30s)
echo Waiting for startup...
set /a attempts=0
:wait_loop
timeout /t 3 /nobreak >nul
curl -s http://localhost:8080/actuator/health 2>nul | findstr /i "UP" >nul
if not errorlevel 1 (
    echo App is UP!
    goto :done
)
set /a attempts+=1
if %attempts% lss 15 goto wait_loop
echo ERROR: App did not start in time
exit /b 1

:done
echo App running. Profile=%1 TTL=%2 Sigma=%3
