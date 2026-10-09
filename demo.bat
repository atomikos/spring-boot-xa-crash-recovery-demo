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
rem Helper for the demo on Windows (same as demo.sh). The app itself runs with mvnw spring-boot:run (see README).
setlocal
pushd "%~dp0"
rem UTF-8 so the emoji in the status output show up
for /f "tokens=2 delims=:." %%c in ('chcp') do set "OLDCP=%%c"
chcp 65001 >nul

set "PSQL=docker compose exec -T postgres psql -U shop -d shop -q"
set "RC=0"

if "%~1"=="order"  goto order
if "%~1"=="crash"  goto crash
if "%~1"=="status" goto status
if "%~1"=="reset"  goto reset
goto usage

:order
set "PRODUCT=%~2"
if "%PRODUCT%"=="" set "PRODUCT=book"
curl -s -X POST "localhost:8080/orders?product=%PRODUCT%" || (echo (no answer - did the app just crash?^) & set "RC=1")
goto end

:crash
if "%~2"=="" (
  echo usage: %~nx0 crash before-send^|before-commit^|after-prepare
  set "RC=1"
  goto end
)
curl -s -X POST "localhost:8080/orders?product=book&crash=%~2" || echo (no answer: the app crashed^)
goto end

:status
set "Q1=SELECT coalesce(o.id, s.order_id) AS \"order\", o.product,"
set "Q1=%Q1% CASE WHEN o.id IS NULL THEN '👻 shipped, but the order does not exist'"
set "Q1=%Q1% WHEN s.order_id IS NULL THEN '❌ NOT shipped'"
set "Q1=%Q1% ELSE '✅ shipped' END AS shipment"
set "Q1=%Q1% FROM purchase_order o FULL JOIN shipment s ON s.order_id = o.id ORDER BY 1"
set "Q2=SELECT gid AS \"in-doubt (prepared) transactions\", prepared FROM pg_prepared_xacts"
%PSQL% -c "%Q1%" -c "%Q2%" || set "RC=1"
goto end

:reset
docker compose down
if exist transaction-logs rmdir /s /q transaction-logs
goto end

:usage
echo usage: %~nx0 order [product] ^| crash before-send^|before-commit^|after-prepare ^| status ^| reset
set "RC=1"

:end
chcp %OLDCP% >nul
popd
endlocal & exit /b %RC%
