@echo off

setlocal enabledelayedexpansion
setlocal enableextensions

call "%~dp0elasticsearch-env.bat" || exit /b 1

rem use a small heap size for the CLI tools, and thus the serial collector to
rem avoid stealing many CPU cycles; a user can override by setting CLI_JAVA_OPTS
set CLI_JAVA_OPTS=-Xms4m -Xmx64m -XX:+UseSerialGC %CLI_JAVA_OPTS%

set LAUNCHER_CLASSPATH=%ES_HOME%/lib/*;%ES_HOME%/lib/cli-launcher/*

rem Ensure ES_TMPDIR is set so the preparer and launcher agree on the descriptor path
if not defined ES_TMPDIR (
  set "ES_TMPDIR=%TEMP%\elasticsearch"
  if not exist "!ES_TMPDIR!" mkdir "!ES_TMPDIR!"
)

rem Run the preparer (server-cli): computes JVM options, auto-configures security,
rem syncs plugins, and writes a launch descriptor file to ES_TMPDIR.
%JAVA% ^
  %CLI_JAVA_OPTS% ^
  -Dcli.name="server" ^
  -Dcli.script="%~dpnx0" ^
  -Dcli.libs="lib/tools/server-cli" ^
  -Des.path.home="%ES_HOME%" ^
  -Des.path.conf="%ES_PATH_CONF%" ^
  -Des.distribution.type="%ES_DISTRIBUTION_TYPE%" ^
  -Des.java.type="%JAVA_TYPE%" ^
  -cp "%LAUNCHER_CLASSPATH%" ^
  org.elasticsearch.launcher.CliToolLauncher ^
  %*

if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

set "DESCRIPTOR_PATH=%ES_TMPDIR%\launch-descriptor.bin"

rem If no descriptor was written (e.g. --version was used), just exit
if not exist "%DESCRIPTOR_PATH%" exit /b 0

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
