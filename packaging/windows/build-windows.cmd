@echo off
rem Builds target\windows\MSA (portable app) and its zip. Arguments are passed on, e.g. -SkipBuild or -LibUsb C:\path\libusb-1.0.dll
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-windows.ps1" %*
