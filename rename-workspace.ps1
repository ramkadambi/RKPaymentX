# Script to rename WellsPaymentEngine to RKPaymentX
# Run this script AFTER closing Cursor/VS Code

$parentDir = "F:\Python Projects\AgenticAI"
$oldName = "WellsPaymentEngine"
$newName = "RKPaymentX"

Write-Host "=== Renaming Workspace Directory ===" -ForegroundColor Cyan
Write-Host ""

$oldPath = Join-Path $parentDir $oldName
$newPath = Join-Path $parentDir $newName

if (-not (Test-Path $oldPath)) {
    Write-Host "[ERROR] Source directory not found: $oldPath" -ForegroundColor Red
    exit 1
}

if (Test-Path $newPath) {
    Write-Host "[ERROR] Target directory already exists: $newPath" -ForegroundColor Red
    exit 1
}

try {
    Write-Host "Renaming: $oldName -> $newName" -ForegroundColor Yellow
    Rename-Item -Path $oldPath -NewName $newName -ErrorAction Stop
    Write-Host "[SUCCESS] Directory renamed successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "New path: $newPath" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Yellow
    Write-Host "1. Reopen the workspace in Cursor using the new folder name" -ForegroundColor White
    Write-Host "2. The workspace should now be: $newPath" -ForegroundColor White
} catch {
    Write-Host "[ERROR] Failed to rename directory: $_" -ForegroundColor Red
    Write-Host ""
    Write-Host "Make sure:" -ForegroundColor Yellow
    Write-Host "- Cursor/VS Code is completely closed" -ForegroundColor White
    Write-Host "- No other processes are using files in the directory" -ForegroundColor White
    Write-Host "- You have write permissions to the parent directory" -ForegroundColor White
    exit 1
}

