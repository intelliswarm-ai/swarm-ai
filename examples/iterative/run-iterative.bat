@echo off
setlocal enabledelayedexpansion

REM SwarmAI Iterative Investment Memo - Windows Batch Script
REM Automated setup and execution using Docker

REM Configuration
set COMPOSE_FILE=docker-compose.yml
set SERVICE_NAME=iterative-app
set DEFAULT_TICKER=NVDA
set DEFAULT_MAX_ITERATIONS=3

REM Colors
set RED=[31m
set GREEN=[32m
set YELLOW=[33m
set BLUE=[34m
set CYAN=[36m
set NC=[0m

REM Check if first argument is help
if "%1"=="--help" goto :show_help
if "%1"=="-h" goto :show_help
if "%1"=="/?" goto :show_help

REM Print header
call :print_header

REM Set ticker and iterations
if "%1"=="" (
    set STOCK_TICKER=%DEFAULT_TICKER%
) else (
    set STOCK_TICKER=%1
)

if "%2"=="" (
    set MAX_ITERATIONS=%DEFAULT_MAX_ITERATIONS%
) else (
    set MAX_ITERATIONS=%2
)

echo %BLUE%Analyzing: %STOCK_TICKER% (max %MAX_ITERATIONS% iterations)%NC%
echo.

REM Check Docker
call :check_docker
if %ERRORLEVEL% neq 0 exit /b 1

REM Build and run
echo %BLUE%Building and starting services...%NC%
docker compose -f %COMPOSE_FILE% up --build

if %ERRORLEVEL% equ 0 (
    echo %GREEN%Iterative investment memo completed!%NC%
    echo.
    echo %GREEN%Check the generated memo in the reports directory.%NC%
) else (
    echo %RED%Iterative workflow failed. Check the logs above.%NC%
    exit /b 1
)

REM Cleanup
call :cleanup

goto :eof

REM ========== Functions ==========

:print_header
echo %CYAN%
echo ============================================================
echo   SwarmAI - Iterative Investment Memo
echo   Process: Execute -^> Review -^> Refine -^> Repeat
echo ============================================================
echo %NC%
exit /b 0

:check_docker
echo Checking Docker installation...
docker --version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo %RED%Docker is not installed or not in PATH.%NC%
    echo Please install Docker Desktop from https://www.docker.com/products/docker-desktop
    exit /b 1
)

docker info >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo %RED%Docker daemon is not running.%NC%
    echo Please start Docker Desktop and try again.
    exit /b 1
)

echo %GREEN%Docker is running%NC%
exit /b 0

:cleanup
echo.
echo %YELLOW%Cleaning up...%NC%
docker compose -f %COMPOSE_FILE% down --remove-orphans >nul 2>&1
exit /b 0

:show_help
call :print_header
echo Usage: %~nx0 [TICKER] [MAX_ITERATIONS]
echo.
echo Arguments:
echo   TICKER          Stock ticker to analyze (default: NVDA)
echo   MAX_ITERATIONS  Maximum review-refine cycles (default: 3)
echo.
echo Examples:
echo   %~nx0                  # Analyze NVDA with 3 iterations
echo   %~nx0 TSLA             # Analyze Tesla with 3 iterations
echo   %~nx0 AAPL 5           # Analyze Apple with up to 5 iterations
echo.
echo Requirements:
echo   - Docker Desktop for Windows
echo   - Windows 10 or later
echo.
exit /b 0
