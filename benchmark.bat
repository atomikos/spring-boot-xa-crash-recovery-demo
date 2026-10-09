@echo off
rem Copyright 2026 Atomikos
rem
rem Licensed under the Apache License, Version 2.0 (the "License");
rem you may not use this file except in compliance with the License.
rem You may obtain a copy of the License at
rem
rem     http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing, software
rem distributed under the License is distributed on an "AS IS" BASIS,
rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and
rem limitations under the License.
rem
rem How much does XA cost? The Windows twin of benchmark.sh: runs OrderServiceBenchmark
rem once per mode and round, each in its own JVM with fresh containers (Docker needed,
rem not the demo's compose.yaml: the demo data stays untouched), then prints the averages.
rem
rem   benchmark.bat                      3 rounds of naive, local-tx and xa
rem   set ROUNDS=1                       then benchmark.bat: a quicker look
rem   benchmark.bat -Dbenchmark.threads=1,8,32 -Dbenchmark.seconds=60
rem                                      anything else goes to Maven; no double quotes,
rem                                      mvnw.cmd rejects them (in PowerShell, single-quote
rem                                      each option: '-Dbenchmark.threads=1,8,32')
setlocal enabledelayedexpansion
pushd "%~dp0"

if not defined MODES set "MODES=naive local-tx xa"
if not defined ROUNDS set "ROUNDS=3"
set "RESULTS=target\benchmark\results.csv"
set "LOG=target\benchmark\last-run.log"
set "RC=0"

set "N=0"
for %%m in (%MODES%) do (
  set "MODE[!N!]=%%m"
  set /a N+=1
)

if not exist target\benchmark mkdir target\benchmark
if exist "%RESULTS%" del "%RESULTS%"
for /l %%r in (1,1,%ROUNDS%) do (
  for /l %%i in (1,1,%N%) do (
    rem rotate the order each round, so no mode always runs first (or last)
    set /a "IDX=(%%i + %%r - 2) %% N"
    for %%x in (!IDX!) do set "MODE=!MODE[%%x]!"
    echo === round %%r/%ROUNDS%: !MODE!
    call mvnw.cmd -q test -Dtest=OrderServiceBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dbenchmark.mode=!MODE! %* > "%LOG%" 2>&1
    if errorlevel 1 (
      findstr /b /c:"[ERROR]" "%LOG%"
      echo benchmark failed: see %LOG% and target\surefire-reports
      set "RC=1"
      goto end
    )
    findstr /r /c:"^mode" /c:"^Atomikos pools" /c:"^naive " /c:"^local-tx " /c:"^xa " /c:"^checked" "%LOG%"
  )
)

echo.
echo === average of %ROUNDS% round(s), Windows %PROCESSOR_ARCHITECTURE% - raw numbers in %RESULTS%
rem batch has no decimals: Windows PowerShell does the averages (dots as decimal
rem separator whatever the locale, like the CSV)
set "PS=$ic = [Globalization.CultureInfo]::InvariantCulture;"
set "PS=%PS% function avg($rows, $column) { $sum = 0.0; foreach ($row in $rows) { $sum += [double]::Parse($row.$column, $ic) }; $sum / $rows.Count };"
set "PS=%PS% $rows = @(Import-Csv '%RESULTS%');"
set "PS=%PS% [string]::Format($ic, '{0,-9} {1,7} {2,10} {3,8} {4,8} {5,9}', 'mode', 'threads', 'orders/s', 'p50 ms', 'p99 ms', 'vs naive');"
set "PS=%PS% foreach ($t in ($rows | Select-Object -ExpandProperty threads -Unique | Sort-Object { [int]$_ })) {"
set "PS=%PS%   $naive = @($rows | Where-Object { $_.mode -eq 'naive' -and $_.threads -eq $t });"
set "PS=%PS%   foreach ($m in $env:MODES.Split(' ', [StringSplitOptions]::RemoveEmptyEntries)) {"
set "PS=%PS%     $run = @($rows | Where-Object { $_.mode -eq $m -and $_.threads -eq $t });"
set "PS=%PS%     if ($run.Count -eq 0) { continue };"
set "PS=%PS%     $tps = avg $run 'orders_per_sec'; $vs = '-';"
set "PS=%PS%     if ($naive.Count -gt 0) { $vs = [string]::Format($ic, '{0:+0;-0;+0}%%', 100 * $tps / (avg $naive 'orders_per_sec') - 100) };"
set "PS=%PS%     [string]::Format($ic, '{0,-9} {1,7} {2,10:0.0} {3,8:0.00} {4,8:0.00} {5,9}', $m, $t, $tps, (avg $run 'p50_ms'), (avg $run 'p99_ms'), $vs) } }"
powershell -NoProfile -ExecutionPolicy Bypass -Command "%PS%" || set "RC=1"

:end
popd
endlocal & exit /b %RC%
