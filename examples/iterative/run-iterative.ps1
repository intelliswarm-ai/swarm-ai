# SwarmAI Iterative Investment Memo - PowerShell Script
# Automated setup and execution using Docker
#
# Usage:
#   .\run-iterative.ps1              # Analyze NVDA (default, 3 iterations)
#   .\run-iterative.ps1 TSLA         # Analyze Tesla
#   .\run-iterative.ps1 AAPL 5       # Analyze Apple with 5 iterations max
#   .\run-iterative.ps1 -Help        # Show help

param(
    [Parameter(Position=0)]
    [string]$StockTicker = "NVDA",

    [Parameter(Position=1)]
    [int]$MaxIterations = 3,

    [switch]$Help
)

# Configuration
$ComposeFile = "docker-compose.yml"
$ServiceName = "iterative-app"

function Print-Header {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  SwarmAI - Iterative Investment Memo" -ForegroundColor Cyan
    Write-Host "  Process: Execute -> Review -> Refine -> Repeat" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host ""
}

function Print-Status {
    param([string]$Message)
    Write-Host "  $Message" -ForegroundColor Green
}

function Print-Error {
    param([string]$Message)
    Write-Host "  $Message" -ForegroundColor Red
}

function Print-Warning {
    param([string]$Message)
    Write-Host "  $Message" -ForegroundColor Yellow
}

function Check-Docker {
    Write-Host "Checking Docker installation..." -ForegroundColor Cyan

    try {
        $dockerVersion = docker --version 2>$null
        if (-not $dockerVersion) {
            throw "Docker not found"
        }
    }
    catch {
        Print-Error "Docker is not installed or not in PATH."
        Write-Host "Please install Docker Desktop from:" -ForegroundColor Yellow
        Write-Host "https://www.docker.com/products/docker-desktop" -ForegroundColor Cyan
        return $false
    }

    try {
        docker info 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Docker daemon not running"
        }
    }
    catch {
        Print-Error "Docker daemon is not running."
        Write-Host "Please start Docker Desktop and try again." -ForegroundColor Yellow
        return $false
    }

    Print-Status "Docker is running"
    return $true
}

function Show-Help {
    Print-Header
    Write-Host "Usage: .\run-iterative.ps1 [TICKER] [MAX_ITERATIONS] [-Help]" -ForegroundColor White
    Write-Host ""
    Write-Host "Parameters:" -ForegroundColor Cyan
    Write-Host "  TICKER          Stock symbol to analyze (default: NVDA)" -ForegroundColor White
    Write-Host "  MAX_ITERATIONS  Maximum review-refine cycles (default: 3)" -ForegroundColor White
    Write-Host "  -Help           Show this help message" -ForegroundColor White
    Write-Host ""
    Write-Host "Examples:" -ForegroundColor Cyan
    Write-Host "  .\run-iterative.ps1              # Analyze NVDA (3 iterations)" -ForegroundColor White
    Write-Host "  .\run-iterative.ps1 TSLA         # Analyze Tesla (3 iterations)" -ForegroundColor White
    Write-Host "  .\run-iterative.ps1 AAPL 5       # Analyze Apple (up to 5 iterations)" -ForegroundColor White
    Write-Host "  .\run-iterative.ps1 MSFT 1       # Single-pass (no refinement)" -ForegroundColor White
    Write-Host ""
    Write-Host "What to watch for in the logs:" -ForegroundColor Cyan
    Write-Host "  ITERATION_STARTED     Each review-refine cycle beginning" -ForegroundColor White
    Write-Host "  NEEDS_REFINEMENT      Reviewer feedback with specific issues" -ForegroundColor White
    Write-Host "  APPROVED              Reviewer satisfied with the output" -ForegroundColor White
    Write-Host ""
    Write-Host "Popular Stocks:" -ForegroundColor Cyan
    Write-Host "  Tech:       NVDA, AAPL, MSFT, GOOGL, META, TSLA" -ForegroundColor White
    Write-Host "  Finance:    JPM, BAC, WFC, GS, MS" -ForegroundColor White
    Write-Host "  Healthcare: JNJ, PFE, UNH, ABBV" -ForegroundColor White
    Write-Host ""
}

function Run-IterativeMemo {
    param([string]$Ticker, [int]$Iterations)

    Print-Header

    Write-Host "  Analyzing: $Ticker (max $Iterations iterations)" -ForegroundColor Blue
    Write-Host ""

    if (-not (Check-Docker)) {
        exit 1
    }

    # Set environment variables for Docker Compose
    $env:STOCK_TICKER = $Ticker
    $env:MAX_ITERATIONS = $Iterations

    Write-Host ""
    Write-Host "  Building and starting services..." -ForegroundColor Blue
    Write-Host "  This may take a few minutes on first run..." -ForegroundColor Cyan
    Write-Host ""

    try {
        docker compose -f $ComposeFile up --build

        if ($LASTEXITCODE -eq 0) {
            Write-Host ""
            Print-Status "Iterative investment memo completed!"
            Write-Host ""
            Write-Host "  Check the generated memo in the reports directory." -ForegroundColor Green
        }
        else {
            Print-Error "Iterative workflow failed. Please check the logs above."
            exit 1
        }
    }
    catch {
        Print-Error "An error occurred during execution: $_"
        exit 1
    }
    finally {
        Write-Host ""
        Print-Warning "Cleaning up Docker containers..."
        docker compose -f $ComposeFile down --remove-orphans 2>$null
    }
}

# Main execution
if ($Help) {
    Show-Help
    exit 0
}

# Validate stock ticker format
if ($StockTicker -notmatch '^[A-Z]{1,5}$') {
    Print-Error "Invalid stock ticker format. Please use 1-5 uppercase letters (e.g., NVDA, AAPL, TSLA)"
    Write-Host "Use -Help parameter for more information." -ForegroundColor Yellow
    exit 1
}

# Validate iterations
if ($MaxIterations -lt 1 -or $MaxIterations -gt 10) {
    Print-Error "MAX_ITERATIONS must be between 1 and 10."
    exit 1
}

Run-IterativeMemo -Ticker $StockTicker -Iterations $MaxIterations
