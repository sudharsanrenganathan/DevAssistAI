# DevAssist AI — Local Startup Script
# Usage: Right-click -> "Run with PowerShell"  OR  in PS terminal: .\run-local.ps1

$root = Split-Path -Parent $MyInvocation.MyCommand.Definition

# ── Load secrets ────────────────────────────────────────────────────────────
$secretsFile = Join-Path $root "local-secrets.ps1"
if (-not (Test-Path $secretsFile)) {
    Write-Host "[ERROR] local-secrets.ps1 not found. Create it first." -ForegroundColor Red
    pause; exit 1
}
. $secretsFile

if ($env:SUPABASE_JWT_SECRET -eq "PASTE_YOUR_JWT_SECRET_HERE") {
    Write-Host ""
    Write-Host "[STOP] You must fill in SUPABASE_JWT_SECRET in local-secrets.ps1" -ForegroundColor Red
    Write-Host "  Go to: Supabase Dashboard -> your project -> Settings -> API -> JWT Secret" -ForegroundColor Yellow
    Write-Host ""
    pause; exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   DevAssist AI - Starting Locally" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ── 1. AI Engine (FastAPI on port 8000) ─────────────────────────────────────
Write-Host "[1/3] Starting AI Engine on http://localhost:8000 ..." -ForegroundColor Green
$aiCmd = @"
cd '$root\ai-engine'
if (-not (Test-Path 'venv')) { python -m venv venv }
.\venv\Scripts\Activate.ps1
pip install -q -r requirements.txt
uvicorn api.api_server:app --host 0.0.0.0 --port 8000 --reload
"@
Start-Process powershell -ArgumentList "-NoExit", "-Command", $aiCmd

Start-Sleep -Seconds 5

# ── 2. Spring Boot Backend (port 8080) ──────────────────────────────────────
Write-Host "[2/3] Starting Backend on http://localhost:8080 ..." -ForegroundColor Green
$backendCmd = @"
`$env:SPRING_DATASOURCE_PASSWORD = '$($env:SPRING_DATASOURCE_PASSWORD)'
`$env:SPRING_DATASOURCE_USERNAME = '$($env:SPRING_DATASOURCE_USERNAME)'
`$env:SPRING_DATASOURCE_URL      = '$($env:SPRING_DATASOURCE_URL)'
`$env:SUPABASE_JWT_SECRET        = '$($env:SUPABASE_JWT_SECRET)'
`$env:SUPABASE_ANON_KEY          = '$($env:SUPABASE_ANON_KEY)'
`$env:AI_ENGINE_URL              = '$($env:AI_ENGINE_URL)'
cd '$root\backend\devassist-backend'
mvn spring-boot:run
"@
Start-Process powershell -ArgumentList "-NoExit", "-Command", $backendCmd

Start-Sleep -Seconds 10

# ── 3. Frontend (python http.server on port 5500) ───────────────────────────
Write-Host "[3/3] Starting Frontend on http://localhost:5500 ..." -ForegroundColor Green
$frontendCmd = @"
cd '$root\frontend'
python -m http.server 5500
"@
Start-Process powershell -ArgumentList "-NoExit", "-Command", $frontendCmd

Start-Sleep -Seconds 2

# ── Open browser ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "Opening browser..." -ForegroundColor Cyan
Start-Process "http://localhost:5500/index.html"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " All services starting up!" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Frontend  -> http://localhost:5500" -ForegroundColor White
Write-Host "  Backend   -> http://localhost:8080" -ForegroundColor White
Write-Host "  AI Engine -> http://localhost:8000" -ForegroundColor White
Write-Host "  API Docs  -> http://localhost:8000/docs" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
