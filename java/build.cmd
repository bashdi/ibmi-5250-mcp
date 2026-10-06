@echo off
rem Builds bin\ibmi-5250-mcp.jar (MCP server + ACS agent in one jar). Needs a JDK (javac, jar) on PATH.
setlocal
if exist "%~dp0build" rmdir /s /q "%~dp0build"
mkdir "%~dp0build\classes"
if not exist "%~dp0bin" mkdir "%~dp0bin"
javac --release 11 -encoding UTF-8 -d "%~dp0build\classes" "%~dp0src\iaccess\Json.java" "%~dp0src\iaccess\Dirs.java" "%~dp0src\iaccess\Boot.java" "%~dp0src\iaccess\AgentImpl.java" "%~dp0src\iaccess\McpServer.java" || exit /b 1
> "%~dp0build\manifest.txt" echo Main-Class: iaccess.McpServer
>> "%~dp0build\manifest.txt" echo Agent-Class: iaccess.Boot
>> "%~dp0build\manifest.txt" echo Can-Redefine-Classes: false
>> "%~dp0build\manifest.txt" echo Can-Retransform-Classes: false
jar cfm "%~dp0bin\ibmi-5250-mcp.jar" "%~dp0build\manifest.txt" -C "%~dp0build\classes" . || exit /b 1
echo built %~dp0bin\ibmi-5250-mcp.jar
