# SwarmAI Stock Analysis Example - PowerShell Script
# Automated setup and execution using Docker
# 
# Usage:
#   .\run-stock.ps1          # Analyze AAPL (default)
#   .\run-stock.ps1 TSLA     # Analyze Tesla
#   .\run-stock.ps1 -Help    # Show help

param(
    [Parameter(Position=0)]
    [string]$StockTicker = "AAPL",
    
    [switch]$Help
)

# Configuration
$ComposeFile = "docker-compose.yml"
$ServiceName = "stock-analysis"

# Color functions for better output
function Write-ColorOutput($ForegroundColor) {
    $fc = $host.UI.RawUI.ForegroundColor
    $host.UI.RawUI.ForegroundColor = $ForegroundColor
    if ($args) {
        Write-Output $args
    }
    $host.UI.RawUI.ForegroundColor = $fc
}

function Print-Header {
    Write-Host ""
    Write-Host "📊 SwarmAI Stock Analysis - Investment Research" -ForegroundColor Blue
    Write-Host "===============================================" -ForegroundColor Blue
    Write-Host ""
}

function Print-Status {
    param([string]$Message)
    Write-Host "✅ $Message" -ForegroundColor Green
}

function Print-Error {
    param([string]$Message)
    Write-Host "❌ $Message" -ForegroundColor Red
}

function Print-Warning {
    param([string]$Message)
    Write-Host "⚠️  $Message" -ForegroundColor Yellow
}

function Check-Docker {
    Write-Host "Checking Docker installation..." -ForegroundColor Cyan
    
    # Check if Docker is installed
    try {
        $dockerVersion = docker --version 2>$null
        if (-not $dockerVersion) {
            throw "Docker not found"
        }
    }
    catch {
        Print-Error "Docker is not installed or not in PATH."
        Write-Host "Please install Docker Desktop for Windows from:" -ForegroundColor Yellow
        Write-Host "https://www.docker.com/products/docker-desktop" -ForegroundColor Cyan
        return $false
    }
    
    # Check if Docker daemon is running
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
    Write-Host "Usage: .\run-stock.ps1 [STOCK_TICKER] [-Help]" -ForegroundColor White
    Write-Host ""
    Write-Host "Parameters:" -ForegroundColor Cyan
    Write-Host "  STOCK_TICKER    Stock symbol to analyze (default: AAPL)" -ForegroundColor White
    Write-Host "  -Help           Show this help message" -ForegroundColor White
    Write-Host ""
    Write-Host "Examples:" -ForegroundColor Cyan
    Write-Host "  .\run-stock.ps1              # Analyze AAPL (default)" -ForegroundColor White
    Write-Host "  .\run-stock.ps1 TSLA         # Analyze Tesla" -ForegroundColor White
    Write-Host "  .\run-stock.ps1 GOOGL        # Analyze Google" -ForegroundColor White
    Write-Host "  .\run-stock.ps1 MSFT         # Analyze Microsoft" -ForegroundColor White
    Write-Host ""
    Write-Host "Popular Stocks to Analyze:" -ForegroundColor Cyan
    Write-Host "  Tech:       AAPL, MSFT, GOOGL, META, NVDA, TSLA" -ForegroundColor White
    Write-Host "  Finance:    JPM, BAC, WFC, GS, MS" -ForegroundColor White
    Write-Host "  Retail:     AMZN, WMT, TGT, HD, COST" -ForegroundColor White
    Write-Host "  Healthcare: JNJ, PFE, UNH, CVS, ABBV" -ForegroundColor White
    Write-Host ""
    Write-Host "Requirements:" -ForegroundColor Cyan
    Write-Host "  - Docker Desktop for Windows" -ForegroundColor White
    Write-Host "  - PowerShell 5.0 or later" -ForegroundColor White
    Write-Host "  - Internet connection for pulling Docker images" -ForegroundColor White
    Write-Host ""
}

function Run-StockAnalysis {
    param([string]$Ticker)
    
    Print-Header
    
    Write-Host "📈 Analyzing Stock: $Ticker" -ForegroundColor Blue
    Write-Host ""
    
    # Check Docker
    if (-not (Check-Docker)) {
        exit 1
    }
    
    # Set environment variable for Docker Compose
    $env:STOCK_TICKER = $Ticker
    
    Write-Host ""
    Write-Host "🔨 Building and starting services..." -ForegroundColor Blue
    Write-Host "This may take a few minutes on first run..." -ForegroundColor Cyan
    Write-Host ""
    
    try {
        # Run Docker Compose
        docker compose -f $ComposeFile up --build
        
        if ($LASTEXITCODE -eq 0) {
            Write-Host ""
            Print-Status "Stock analysis workflow completed!"
            Write-Host ""
            Write-Host "📄 Check the generated report for investment insights!" -ForegroundColor Green
            Write-Host "📁 Report location: stock_analysis_report.md" -ForegroundColor Cyan
        }
        else {
            Print-Error "Stock analysis failed. Please check the logs above."
            exit 1
        }
    }
    catch {
        Print-Error "An error occurred during execution: $_"
        exit 1
    }
    finally {
        # Cleanup
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

# Validate stock ticker format (basic validation)
if ($StockTicker -notmatch '^[A-Z]{1,5}$') {
    Print-Error "Invalid stock ticker format. Please use 1-5 uppercase letters (e.g., AAPL, MSFT, GOOGL)"
    Write-Host "Use -Help parameter for more information." -ForegroundColor Yellow
    exit 1
}

# Run the analysis
Run-StockAnalysis -Ticker $StockTicker