@echo off
title PodcastStudio v4
cd /d "%~dp0"
"%~dp0jdk\bin\javaw.exe" --module-path "%~dp0javafx-sdk\lib" --add-modules javafx.controls,javafx.graphics,javafx.base -jar "%~dp0app\PodcastStudio.jar"