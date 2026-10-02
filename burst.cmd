@echo off
rem One-command burst: burst.cmd <BASE_URL> [options]
if "%~1"=="" (
  echo usage: burst.cmd ^<BASE_URL^> [options]   e.g. burst.cmd http://localhost:8080
  exit /b 2
)
where java >nul 2>nul
if errorlevel 1 (
  echo java not found. Install a JDK 21+ from https://adoptium.net and put it on PATH.
  exit /b 2
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JV=%%~v
for /f "delims=. tokens=1" %%m in ("%JV%") do set JMAJOR=%%m
if %JMAJOR% LSS 21 (
  echo Java 21+ is required, found %JV%. Install JDK 21 from https://adoptium.net
  exit /b 2
)
java "%~dp0burst\Burst.java" %*
exit /b %ERRORLEVEL%
