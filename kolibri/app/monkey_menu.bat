@echo off

REM --- V V V --- KONFIGURATION --- V V V ---
REM Hier den gewünschten Pfad für die Reports eintragen.
set "REPORTS_DIR=..\monkey_reports"
REM --- A A A --- KONFIGURATION --- A A A ---

color 0A
title Kolibri Launcher - Monkey Test Menu

:MENU
cls
echo ============================================
echo    KOLIBRI LAUNCHER - MONKEY TEST MENU
echo ============================================
echo.
echo  1. Quick Test (500 events, ~1 minute)
echo  2. Rotation Stress Test (200 rotations)
echo  3. App Launch Test (500 launches)
echo  4. Navigation Stress (300 nav events)
echo  5. Full Stress Test (5000 events, ~10 min)
echo  6. Extreme Test (10000 events, ~20 min)
echo  7. View Current Logcat
echo  8. Clear Logcat
echo  9. Exit
echo.
echo Reports werden in "%REPORTS_DIR%" gespeichert.
echo ============================================
set /p choice="Choose option (1-9): "

if "%choice%"=="1" goto QUICK
if "%choice%"=="2" goto ROTATION
if "%choice%"=="3" goto LAUNCH
if "%choice%"=="4" goto NAVIGATION
if "%choice%"=="5" goto STRESS
if "%choice%"=="6" goto EXTREME
if "%choice%"=="7" goto LOGCAT
if "%choice%"=="8" goto CLEAR
if "%choice%"=="9" goto EXIT
echo Invalid choice!
timeout /t 2 >nul
goto MENU

:QUICK
echo.
echo Running Quick Test (500 events)...
adb shell monkey -p com.github.reygnn.kolibri_launcher -v 500
goto RESULT

:ROTATION
echo.
echo Running Rotation Stress Test...
adb shell monkey -p com.github.reygnn.kolibri_launcher --pct-rotation 80 -v 200
goto RESULT

:LAUNCH
echo.
echo Running App Launch Test...
adb shell monkey -p com.github.reygnn.kolibri_launcher --pct-touch 60 --pct-appswitch 20 -v 500
goto RESULT

:NAVIGATION
echo.
echo Running Navigation Stress Test...
adb shell monkey -p com.github.reygnn.kolibri_launcher --pct-nav 50 --pct-majornav 30 -v 300
goto RESULT

:STRESS
echo.
echo Running Full Stress Test (5000 events)...
echo This will take about 10 minutes...
REM -- Erstellt den Ordner aus der Variable, falls er nicht existiert --
if not exist "%REPORTS_DIR%" mkdir "%REPORTS_DIR%"
REM -- Der Output wird nun in den in der Variable definierten Ordner umgeleitet --
adb shell monkey -p com.github.reygnn.kolibri_launcher -v -v -v --throttle 100 --ignore-timeouts 5000 > "%REPORTS_DIR%\monkey_results.txt"
echo Results saved to: %REPORTS_DIR%\monkey_results.txt
goto RESULT

:EXTREME
echo.
echo Running EXTREME Test (10000 events)...
echo This will take about 20 minutes...
REM -- Erstellt den Ordner aus der Variable, falls er nicht existiert --
if not exist "%REPORTS_DIR%" mkdir "%REPORTS_DIR%"
REM -- Der Output wird nun in den in der Variable definierten Ordner umgeleitet --
adb shell monkey -p com.github.reygnn.kolibri_launcher -v -v -v --throttle 50 --ignore-crashes --ignore-timeouts 10000 > "%REPORTS_DIR%\monkey_extreme.txt"
echo Results saved to: %REPORTS_DIR%\monkey_extreme.txt
goto RESULT

:LOGCAT
echo.
echo Current Logcat (last 100 lines):
echo ============================================
adb logcat -d -t 100
echo ============================================
pause
goto MENU

:CLEAR
echo.
echo Clearing logcat...
adb logcat -c
echo Logcat cleared!
timeout /t 2 >nul
goto MENU

:RESULT
echo.
if %errorlevel% equ 0 (
    echo Test completed successfully!
) else (
    echo Test completed with errors - check logcat!
)
echo.
echo Press any key to return to menu...
pause >nul
goto MENU

:EXIT
echo.
echo Goodbye!
timeout /t 1 >nul
exit