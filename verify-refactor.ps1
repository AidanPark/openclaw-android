# Verify Refactor Script for OpenClaw Android
# This script validates the refactoring changes without requiring a full build

$ErrorActionPreference = "Stop"
$exitCode = 0

Write-Host "======================================" -ForegroundColor Cyan
Write-Host "OpenClaw Android Refactoring Verification" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan

$checks = @()

# 1. Verify AndroidManifest.xml changes
Write-Host "`n[1/12] Checking AndroidManifest.xml..." -ForegroundColor Yellow
$manifest = Get-Content "d:\Proyectos\Android\openclaw-android\android\app\src\main\AndroidManifest.xml" -Raw
if ($manifest -match "MANAGE_EXTERNAL_STORAGE") {
    Write-Host "  FAIL: MANAGE_EXTERNAL_STORAGE still present" -ForegroundColor Red
    $checks += $false
} else {
    Write-Host "  PASS: MANAGE_EXTERNAL_STORAGE removed" -ForegroundColor Green
    $checks += $true
}

# 2. Verify SystemBridge.kt - runCommand removed
Write-Host "`n[2/12] Checking SystemBridge.kt..." -ForegroundColor Yellow
$systemBridge = Get-Content "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\bridge\SystemBridge.kt" -Raw
if ($systemBridge -match "fun runCommand\(" -or $systemBridge -match "fun runCommandAsync\(") {
    Write-Host "  FAIL: runCommand/runCommandAsync still present" -ForegroundColor Red
    $checks += $false
} else {
    Write-Host "  PASS: runCommand/runCommandAsync removed" -ForegroundColor Green
    $checks += $true
}
if ($systemBridge -match "lifecycleScope" -and $systemBridge -match "Dispatchers.IO") {
    Write-Host "  PASS: getStorageInfo uses coroutines" -ForegroundColor Green
} else {
    Write-Host "  FAIL: getStorageInfo may not use coroutines" -ForegroundColor Red
}

# 3. Verify JsBridgeFacade.kt - dangerous methods removed
Write-Host "`n[3/12] Checking JsBridgeFacade.kt..." -ForegroundColor Yellow
$jsBridge = Get-Content "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\bridge\JsBridgeFacade.kt" -Raw
$dangerousMethods = @("runCommand", "runCommandAsync", "launchGateway", "applyUpdate", "getApkUpdateInfo", "fixScriptPermissions")
$foundDangerous = $dangerousMethods | Where-Object { $jsBridge -match "fun $_" }
if ($foundDangerous) {
    Write-Host "  FAIL: Dangerous methods found: $($foundDangerous -join ', ')" -ForegroundColor Red
    $checks += $false
} else {
    Write-Host "  PASS: Dangerous methods removed" -ForegroundColor Green
    $checks += $true
}

# 4. Verify PopupWindowCompatGingerbread deleted
Write-Host "`n[4/12] Checking PopupWindowCompatGingerbread removed..." -ForegroundColor Yellow
if (Test-Path "d:\Proyectos\Android\openclaw-android\android\terminal-view\src\main\java\com\termux\view\support\PopupWindowCompatGingerbread.java") {
    Write-Host "  FAIL: PopupWindowCompatGingerbread.java still exists" -ForegroundColor Red
    $checks += $false
} else {
    Write-Host "  PASS: PopupWindowCompatGingerbread.java deleted" -ForegroundColor Green
    $checks += $true
}

# 5. Verify TextSelectionHandleView uses direct methods
Write-Host "`n[5/12] Checking TextSelectionHandleView.java..." -ForegroundColor Yellow
$textHandle = Get-Content "d:\Proyectos\Android\openclaw-android\android\terminal-view\src\main\java\com\termux\view\textselection\TextSelectionHandleView.java" -Raw
if ($textHandle -match "import.*PopupWindowCompatGingerbread") {
    Write-Host "  FAIL: Still imports PopupWindowCompatGingerbread" -ForegroundColor Red
    $checks += $false
} else {
    Write-Host "  PASS: PopupWindowCompatGingerbread import removed" -ForegroundColor Green
    $checks += $true
}
if ($textHandle -match "setWindowLayoutType.*TYPE_APPLICATION_SUB_PANEL") {
    Write-Host "  PASS: Uses direct setWindowLayoutType method" -ForegroundColor Green
} else {
    Write-Host "  FAIL: May not use direct setWindowLayoutType" -ForegroundColor Red
}

# 6. Verify TerminalSession.java has ParcelFileDescriptor field
Write-Host "`n[6/12] Checking TerminalSession.java..." -ForegroundColor Yellow
$terminalSession = Get-Content "d:\Proyectos\Android\openclaw-android\android\terminal-emulator\src\main\java\com\termux\terminal\TerminalSession.java" -Raw
if ($terminalSession -match "private ParcelFileDescriptor mTerminalParcelFileDescriptor") {
    Write-Host "  PASS: mTerminalParcelFileDescriptor field added" -ForegroundColor Green
    $checks += $true
} else {
    Write-Host "  FAIL: mTerminalParcelFileDescriptor field not found" -ForegroundColor Red
    $checks += $false
}
if ($terminalSession -match "mTerminalParcelFileDescriptor.*close\(\)") {
    Write-Host "  PASS: ParcelFileDescriptor is closed in cleanupResources" -ForegroundColor Green
} else {
    Write-Host "  FAIL: ParcelFileDescriptor may not be closed properly" -ForegroundColor Red
}

# 7. Verify SettingsAdvanced.tsx uses native events
Write-Host "`n[7/12] Checking SettingsAdvanced.tsx..." -ForegroundColor Yellow
$settingsAdvanced = Get-Content "d:\Proyectos\Android\openclaw-android\android\www\src\screens\SettingsAdvanced.tsx" -Raw
# Check for useNativeEvent with setup_progress
$hasUseNativeEvent = $settingsAdvanced.Contains('useNativeEvent') -and $settingsAdvanced.Contains('"setup_progress"')
if ($hasUseNativeEvent) {
    Write-Host "  PASS: Listens to setup_progress events" -ForegroundColor Green
    $checks += $true
} else {
    Write-Host "  FAIL: Does not listen to setup_progress events" -ForegroundColor Red
    $checks += $false
}
# Check for the old setTimeout pattern - should have been removed from handleInstallProot
$hasOldTimeout = $settingsAdvanced.Contains('handleInstallProot') -and $settingsAdvanced.Contains('setTimeout') -and $settingsAdvanced.Contains('3000')
if ($hasOldTimeout) {
    Write-Host "  WARN: Still has setTimeout(3000) pattern - verify it's not for getSetupStatus" -ForegroundColor Yellow
} else {
    Write-Host "  PASS: Old setTimeout pattern removed" -ForegroundColor Green
}

# 8. Verify SettingsTools.tsx handles uninstall properly
Write-Host "`n[8/12] Checking SettingsTools.tsx..." -ForegroundColor Yellow
$settingsTools = Get-Content "d:\Proyectos\Android\openclaw-android\android\www\src\screens\SettingsTools.tsx" -Raw
if ($settingsTools -match "operation.*uninstall") {
    Write-Host "  PASS: Handles uninstall operation via events" -ForegroundColor Green
    $checks += $true
} else {
    Write-Host "  FAIL: Does not handle uninstall via events" -ForegroundColor Red
    $checks += $false
}

# 9. Verify Setup.tsx has cancelable timeout
Write-Host "`n[9/12] Checking Setup.tsx..." -ForegroundColor Yellow
$setup = Get-Content "d:\Proyectos\Android\openclaw-android\android\www\src\screens\Setup.tsx" -Raw
if ($setup -match "useRef.*NodeJS.Timeout" -and $setup -match "autoNavigateTimeoutRef") {
    Write-Host "  PASS: Has cancelable autoNavigate timeout" -ForegroundColor Green
    $checks += $true
} else {
    Write-Host "  FAIL: May not have cancelable timeout" -ForegroundColor Red
    $checks += $false
}

# 10. Verify GlibcRunner.kt has process destruction
Write-Host "`n[10/12] Checking GlibcRunner.kt..." -ForegroundColor Yellow
$glibcRunner = Get-Content "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\core\process\GlibcRunner.kt" -Raw
if ($glibcRunner.Contains("destroyForcibly")) {
    Write-Host "  PASS: Has destroyForcibly for API < 26" -ForegroundColor Green
    $checks += $true
} else {
    Write-Host "  FAIL: Missing destroyForcibly" -ForegroundColor Red
    $checks += $false
}

# 11. Verify TermuxBootstrap files exist (restored for ONLINE mode)
Write-Host "`n[11/12] Checking TermuxBootstrap files exist..." -ForegroundColor Yellow
$termuxFiles = @(
    "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\TermuxBootstrapManager.kt",
    "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\core\bootstrap\TermuxBootstrapOrchestrator.kt",
    "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\core\bootstrap\TermuxBootstrapDownloader.kt",
    "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\core\bootstrap\TermuxBootstrapExtractor.kt"
)
$missingTermux = $termuxFiles | Where-Object { -not (Test-Path $_) }
if ($missingTermux) {
    Write-Host "  FAIL: Some TermuxBootstrap files missing: $($missingTermux -join ', ')" -ForegroundColor Red
    $checks += $false
} else {
    Write-Host "  PASS: All TermuxBootstrap files exist (restored for ONLINE mode)" -ForegroundColor Green
    $checks += $true
}
# Check for conflict detection in TermuxBootstrapManager
$termuxManager = Get-Content "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\TermuxBootstrapManager.kt" -Raw
if ($termuxManager.Contains("hasConflictingSystem") -and $termuxManager.Contains("SISTEMAS MUTUAMENTE EXCLUYENTES")) {
    Write-Host "  PASS: TermuxBootstrapManager has conflict detection" -ForegroundColor Green
} else {
    Write-Host "  WARN: Conflict detection may be missing" -ForegroundColor Yellow
}

# 12. Verify ProotManager uses offline extraction
Write-Host "`n[12/12] Checking ProotManager.kt..." -ForegroundColor Yellow
$prootManager = Get-Content "d:\Proyectos\Android\openclaw-android\android\app\src\main\java\com\openclaw\android\ProotManager.kt" -Raw
if ($prootManager -match "extractProotFromAssets" -and $prootManager -match "extractRootfsFromAssets") {
    Write-Host "  PASS: Uses offline asset extraction" -ForegroundColor Green
    $checks += $true
} else {
    Write-Host "  FAIL: Does not use offline asset extraction" -ForegroundColor Red
    $checks += $false
}
if ($prootManager -match "ProotBinaryDownloader" -or $prootManager -match "ProotRootfsDownloader") {
    Write-Host "  FAIL: Still references downloader classes" -ForegroundColor Red
}

# Summary
Write-Host "`n======================================" -ForegroundColor Cyan
Write-Host "Verification Summary" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan

$passed = ($checks | Where-Object { $_ -eq $true }).Count
$total = $checks.Count

if ($passed -eq $total) {
    $color = "Green"
} else {
    $color = "Yellow"
}
Write-Host "Passed: $passed / $total checks" -ForegroundColor $color

if ($passed -eq $total) {
    Write-Host "`n✅ ALL CHECKS PASSED!" -ForegroundColor Green
    Write-Host "The refactoring appears to be complete and correct." -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n⚠️  SOME CHECKS FAILED" -ForegroundColor Yellow
    Write-Host "Review the failures above." -ForegroundColor Yellow
    exit 1
}
