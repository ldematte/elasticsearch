@echo off

setlocal enabledelayedexpansion
setlocal enableextensions

call "%~dp0elasticsearch-env.bat" || exit /b 1

rem use a small heap size for the CLI tools, and thus the serial collector to
rem avoid stealing many CPU cycles; a user can override by setting CLI_JAVA_OPTS
set CLI_JAVA_OPTS=-Xms4m -Xmx64m -XX:+UseSerialGC %CLI_JAVA_OPTS%

set LAUNCHER_CLASSPATH=%ES_HOME%/lib/*;%ES_HOME%/lib/cli-launcher/*

rem Run the preparer (server-cli): computes JVM options, auto-configures security,
rem syncs plugins, and writes a launch descriptor file. The descriptor path is
rem captured from stdout.
for /f "delims=" %%i in ('%JAVA% %CLI_JAVA_OPTS% -Dcli.name="server" -Dcli.script="%~dpnx0" -Dcli.libs="lib/tools/server-cli" -Des.path.home="%ES_HOME%" -Des.path.conf="%ES_PATH_CONF%" -Des.distribution.type="%ES_DISTRIBUTION_TYPE%" -Des.java.type="%JAVA_TYPE%" -cp "%LAUNCHER_CLASSPATH%" org.elasticsearch.launcher.CliToolLauncher %*') do set DESCRIPTOR_PATH=%%i
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

rem If no descriptor path was printed (e.g. --version was used), just exit
if "%DESCRIPTOR_PATH%"=="" exit /b 0

set LAUNCHER_LIBS=%ES_HOME%/lib/tools/server-launcher/*

rem Run the launcher: reads the descriptor, spawns the server JVM, and manages
rem its lifecycle. On Windows we run sequentially (no exec equivalent).
%JAVA% ^
  %CLI_JAVA_OPTS% ^
  -cp "%LAUNCHER_LIBS%" ^
  org.elasticsearch.server.launcher.ServerLauncher ^
  "%DESCRIPTOR_PATH%"

exit /b %ERRORLEVEL%

endlocal
endlocal
