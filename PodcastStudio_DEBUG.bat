@echo off
title PodcastStudio v4 - Debug
cd /d "%~dp0"
echo Starte PodcastStudio...
echo.
"%~dp0jdk\bin\java.exe" --module-path "%~dp0javafx-sdk\lib" --add-modules javafx.controls,javafx.graphics,javafx.base -jar "%~dp0app\PodcastStudio.jar"
echo.
echo ========================================
echo  Exit-Code: %ERRORLEVEL%
echo ========================================
echo.
pause