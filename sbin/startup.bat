@echo off
setlocal enabledelayedexpansion
rem 将字符集换成GBK
chcp 936 >nul 2>nul

cd /d %~dp0\..
set APP_NAME="mesh_server"
set HOME=%cd%
set LIBPATH=%HOME%\libs
set LOGS=%HOME%\logs
set CP=%HOME%\bin\main

for %%j in (%LIBPATH%\*.jar) do (
    set CP=!CP!;%%j
)

for %%j in (%HOME%\sbin\dependency\*.jar) do (
    set CP=!CP!;%%j
)

set OPTS=-Dname=%APP_NAME% -Xms128M -Xmx512m -XX:MaxDirectMemorySize=256m -Dfile.encoding=UTF-8
set OPTS=%OPTS% -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m
set OPTS=%OPTS% -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=%LOGS%\HeapDump.hprof
rem netty需要的配置，java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent
set OPTS=%OPTS% --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
rem netty提示Reflective setAccessible(true) disabled
set OPTS=%OPTS% -Dio.netty.tryReflectionSetAccessible=true
rem netty提示java.nio.DirectByteBuffer.<init>(long, {int,long}): unavailable
set OPTS=%OPTS% --add-opens java.base/java.nio=ALL-UNNAMED
rem 解决日志配置文件加载问题
set OPTS=%OPTS% -Dlogs.root=%LOGS% -Dlogback.configurationFile=%HOME%\conf\logback.xml
set OPTS=%OPTS% -classpath %CP%

java %OPTS% cn.net.zhijian.platform.ServerMain %1 %2 %3