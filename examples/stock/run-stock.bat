@echo off
setlocal enabledelayedexpansion

REM SwarmAI Stock Analysis Example - Windows Batch Script
REM Automated setup and execution using Docker

REM Configuration
set COMPOSE_FILE=docker-compose.yml
set SERVICE_NAME=stock-analysis
set DEFAULT_TICKER=AAPL

REM Colors for output (Windows 10+ supports ANSI colors)
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

REM Check Docker
call :check_docker
if %ERRORLEVEL% neq 0 exit /b 1

REM Set environment variable for Docker Compose
set STOCK_TICKER=%STOCK_TICKER%

REM Build and run with Docker Compose
echo %BLUE%🔨 Building and starting services...%NC%
docker compose -f %COMPOSE_FILE% up --build

if %ERRORLEVEL% equ 0 (
    echo %GREEN%✅ Stock analysis workflow completed!%NC%
    echo.
    echo %GREEN%📄 Check the generated report for investment insights!%NC%
) else (
    echo %RED%❌ Stock analysis failed. Please check the logs above.%NC%
    exit /b 1
)

REM Cleanup
call :cleanup

goto :eof

REM ========== Functions ==========

:print_header
echo %BLUE%
echo 📊 SwarmAI Stock Analysis - Investment Research
echo ===============================================
echo %NC%
exit /b 0

:check_docker
echo Checking Docker installation...
docker --version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo %RED%❌ Docker is not installed or not in PATH.%NC%
    echo Please install Docker Desktop for Windows from https://www.docker.com/products/docker-desktop
    exit /b 1
)

docker info >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo %RED%❌ Docker daemon is not running.%NC%
    echo Please start Docker Desktop and try again.
    exit /b 1
)

echo %GREEN%✅ Docker is running%NC%
exit /b 0

:cleanup
echo.
echo %YELLOW%🧹 Cleaning up...%NC%
docker compose -f %COMPOSE_FILE% down --remove-orphans >nul 2>&1
exit /b 0

:show_help
call :print_header
echo Usage: %~nx0 [STOCK_TICKER]
echo.
echo Examples:
echo   %~nx0           # Analyze AAPL (default)
echo   %~nx0 TSLA      # Analyze Tesla
echo   %~nx0 GOOGL     # Analyze Google
echo   %~nx0 MSFT      # Analyze Microsoft
echo.
echo Requirements:
echo   - Docker Desktop for Windows
echo   - Windows 10 or later (for ANSI color support)
echo.
exit /b 0