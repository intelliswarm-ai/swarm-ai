@echo off
setlocal enabledelayedexpansion

REM SwarmAI Stock Analysis - Local Execution with Ollama (Windows)
REM Requires: Java 21+, Maven, Ollama running locally

REM Configuration
set DEFAULT_TICKER=AAPL
set OLLAMA_URL=http://localhost:11434
set MAIN_CLASS=ai.intelliswarm.swarmai.SwarmAIApplication

REM Colors (Windows 10+ ANSI support)
set RED=[31m
set GREEN=[32m
set YELLOW=[33m
set BLUE=[34m
set NC=[0m

REM Check if first argument is help
if "%1"=="--help" goto :show_help
if "%1"=="-h" goto :show_help
if "%1"=="/?" goto :show_help

REM Print header
call :print_header

REM Set stock ticker
if "%1"=="" (
    set STOCK_TICKER=%DEFAULT_TICKER%
) else (
    set STOCK_TICKER=%1
)

echo %BLUE%📈 Analyzing Stock: %STOCK_TICKER%%NC%
echo.

REM Check prerequisites
call :check_java
if %ERRORLEVEL% neq 0 exit /b 1

call :check_ollama
if %ERRORLEVEL% neq 0 exit /b 1

call :check_maven
if %ERRORLEVEL% neq 0 exit /b 1

REM Set environment variables
set SPRING_PROFILES_ACTIVE=local
set SPRING_AI_OLLAMA_BASE_URL=%OLLAMA_URL%

REM Navigate to project root (assuming we're in examples/stock)
cd ..\..

REM Build project if needed
if not exist "target\swarmai-framework-1.0.0-SNAPSHOT.jar" (
    echo %YELLOW%Building project...%NC%
    call mvn clean package -DskipTests
    if %ERRORLEVEL% neq 0 (
        echo %RED%❌ Build failed%NC%
        exit /b 1
    )
)

REM Run the stock analysis
echo.
echo %BLUE%🚀 Starting stock analysis workflow...%NC%
echo %YELLOW%This may take 8-12 minutes to complete...%NC%
echo.

java -jar target\swarmai-framework-1.0.0-SNAPSHOT.jar stock-analysis %STOCK_TICKER%

if %ERRORLEVEL% equ 0 (
    echo.
    echo %GREEN%✅ Stock analysis completed successfully!%NC%
    echo %GREEN%📄 Check stock_analysis_report.md for the full report%NC%
) else (
    echo %RED%❌ Stock analysis failed%NC%
    exit /b 1
)

goto :eof

REM ========== Functions ==========

:print_header
echo %BLUE%
echo 📊 SwarmAI Stock Analysis - Local Execution
echo ============================================
echo %NC%
exit /b 0

:check_java
echo Checking Java installation...
java -version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo %RED%❌ Java is not installed or not in PATH%NC%
    echo Please install Java 21 or later from:
    echo https://adoptium.net/
    exit /b 1
)

REM Check Java version (basic check)
for /f tokens^=3 %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VERSION=%%i
echo Found Java version: %JAVA_VERSION%

echo %GREEN%✅ Java is installed%NC%
exit /b 0

:check_ollama
echo Checking Ollama...
curl -s %OLLAMA_URL%/api/tags >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo %RED%❌ Ollama is not running at %OLLAMA_URL%%NC%
    echo.
    echo Please start Ollama:
    echo   1. Download from https://ollama.ai
    echo   2. Run: ollama serve
    echo   3. Pull model: ollama pull llama3.2:3b
    exit /b 1
)

echo %GREEN%✅ Ollama is running%NC%

REM Check if model is available
curl -s %OLLAMA_URL%/api/tags | findstr "llama3.2" >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo %YELLOW%⚠️  llama3.2 model not found. Pulling it now...%NC%
    ollama pull llama3.2:3b
)

exit /b 0

:check_maven
echo Checking Maven...
mvn -version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo %YELLOW%⚠️  Maven not found, will try using mvnw wrapper%NC%
    if exist "mvnw.cmd" (
        echo %GREEN%✅ Found Maven wrapper%NC%
        set MVN_CMD=mvnw.cmd
    ) else (
        echo %RED%❌ Neither Maven nor mvnw wrapper found%NC%
        echo Please install Maven from: https://maven.apache.org/
        exit /b 1
    )
) else (
    echo %GREEN%✅ Maven is installed%NC%
    set MVN_CMD=mvn
)
exit /b 0

:show_help
call :print_header
echo Usage: %~nx0 [STOCK_TICKER]
echo.
echo Run stock analysis locally using Ollama for LLM inference
echo.
echo Examples:
echo   %~nx0           # Analyze AAPL (default)
echo   %~nx0 TSLA      # Analyze Tesla
echo   %~nx0 GOOGL     # Analyze Google
echo   %~nx0 MSFT      # Analyze Microsoft
echo.
echo Requirements:
echo   - Java 21 or later
echo   - Maven (or mvnw wrapper)
echo   - Ollama running locally with llama3.2:3b model
echo   - Windows 10 or later
echo.
echo Setup Instructions:
echo   1. Install Java 21: https://adoptium.net/
echo   2. Install Ollama: https://ollama.ai
echo   3. Start Ollama: ollama serve
echo   4. Pull model: ollama pull llama3.2:3b
echo   5. Run this script
echo.
exit /b 0