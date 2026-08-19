@echo off
rem =============================================================================
rem  mesh.bat - mesh_server management entry
rem  A thin wrapper around the PS1 scripts to simplify command line input.
rem
rem  Usage:
rem    mesh.bat start|stop|restart|status [om_pwd [main_bios]]
rem
rem  Examples:
rem    mesh.bat start       start server (background daemon, auto-restart on crash)
rem    mesh.bat status      show status, health check and CPU/memory usage
rem    mesh.bat stop        stop server
rem    mesh.bat restart     restart server
rem    mesh.bat             (no args) show usage
rem
rem  Note:
rem    Prefers mesh2.ps1 (the improved version, formerly mesh1.ps1);
rem    falls back to mesh.ps1 if it does not exist.
rem =============================================================================
setlocal

rem ---- pick the PowerShell script to call ----
set "PS_SCRIPT=mesh.ps1"
rem ---- no arguments: let the PowerShell script show usage, then pause ----
if not "%~1"=="" goto :run

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0%PS_SCRIPT%"
set "RC=%errorlevel%"
echo.
pause
exit /b %RC%

:run
rem ---- pass through all arguments ----
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0%PS_SCRIPT%" %*
exit /b %errorlevel%
