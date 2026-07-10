<#
.SYNOPSIS
Runs the full Finora verification suite: backend tests, frontend lint,
typecheck, unit tests and production build. Add -E2E to include the
Playwright suite (requires the API running on :8080 with PostgreSQL).

.EXAMPLE
pwsh scripts/verify.ps1
pwsh scripts/verify.ps1 -E2E
#>
[CmdletBinding()]
param(
    [switch]$E2E
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$failures = @()

function Invoke-Step {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [scriptblock]$Command
    )
    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    Push-Location $WorkingDirectory
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) {
            $script:failures += $Name
            Write-Host "FALHOU: $Name (exit $LASTEXITCODE)" -ForegroundColor Red
        } else {
            Write-Host "OK: $Name" -ForegroundColor Green
        }
    } finally {
        Pop-Location
    }
}

Invoke-Step -Name 'Backend: mvnw test' -WorkingDirectory (Join-Path $root 'apps/api') -Command {
    .\mvnw.cmd -B test
}

$web = Join-Path $root 'apps/web'
Invoke-Step -Name 'Frontend: lint' -WorkingDirectory $web -Command { npm run lint }
Invoke-Step -Name 'Frontend: typecheck' -WorkingDirectory $web -Command { npm run typecheck }
Invoke-Step -Name 'Frontend: unit tests' -WorkingDirectory $web -Command { npm run test }
Invoke-Step -Name 'Frontend: build' -WorkingDirectory $web -Command { npm run build }

if ($E2E) {
    Invoke-Step -Name 'E2E: Playwright' -WorkingDirectory $web -Command { npm run e2e }
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "Verificação falhou em: $($failures -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host 'Verificação completa: tudo verde.' -ForegroundColor Green
