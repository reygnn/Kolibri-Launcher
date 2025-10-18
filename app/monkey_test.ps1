$PACKAGE = "com.github.reygnn.kolibri_launcher"
$EVENTS = 10000
$OUTPUT_DIR = "..\monkey_reports"

# Erstelle Output Directory
New-Item -ItemType Directory -Force -Path $OUTPUT_DIR | Out-Null

# Timestamp
$TIMESTAMP = Get-Date -Format "yyyyMMdd_HHmmss"
$OUTPUT_FILE = "$OUTPUT_DIR\monkey_test_$TIMESTAMP.txt"

Write-Host "Starting Monkey Test..." -ForegroundColor Cyan
Write-Host "Package: $PACKAGE"
Write-Host "Events: $EVENTS"
Write-Host "Output: $OUTPUT_FILE"
Write-Host ""

# Führe Monkey Test aus
$monkeyCommand = "monkey -p $PACKAGE -v -v -v --throttle 100 --pct-touch 40 --pct-motion 25 --pct-nav 15 --pct-majornav 10 --pct-syskeys 5 --pct-appswitch 5 --ignore-timeouts --monitor-native-crashes $EVENTS"

adb shell $monkeyCommand *> $OUTPUT_FILE

# Prüfe auf Crashes
if (Select-String -Path $OUTPUT_FILE -Pattern "CRASH" -Quiet) {
    Write-Host ""
    Write-Host "CRASHES FOUND! Check $OUTPUT_FILE" -ForegroundColor Red
    Select-String -Path $OUTPUT_FILE -Pattern "CRASH" -Context 0,20
    exit 1
} else {
    Write-Host ""
    Write-Host "No crashes found!" -ForegroundColor Green
    exit 0
}