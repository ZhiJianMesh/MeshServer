@echo off
setlocal enabledelayedexpansion
rem 将字符集换成GBK
chcp 936 >nul 2>nul

cd /d %~dp0\..
set HOME=%cd%
set LIBPATH=%HOME%\libs
set CP=%HOME%\bin\main
set APP_NAME="mesh_command"

for %%j in (%LIBPATH%\*.jar) do (
    set CP=!CP!;%%j
)

for %%j in (%HOME%\sbin\dependency\*.jar) do (
    set CP=!CP!;%%j
)

set OPTS=-Dname=%APP_NAME% -Xms100M -XX:+UseSerialGC -Dfile.encoding=UTF-8
rem 解决日志配置文件加载问题
set OPTS=%OPTS% -Dlogback.configurationFile=%HOME%\conf\cmdlogback.xml -classpath %CP%

java %OPTS% cn.net.zhijian.platform.ToolMain %1 %2 %3 %4 %5 %6 %7 %8 %9