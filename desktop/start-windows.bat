@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem 不写死版本号：发版时只换 jar，脚本不用跟着改
set "JAR="
for /f "delims=" %%f in ('dir /b /o-n DownloadGuard-Desktop-*.jar 2^>nul') do if not defined JAR set "JAR=%%f"
rem 开发时 jar 在 ../dist，直接双击脚本也能起来
if not defined JAR for /f "delims=" %%f in ('dir /b /o-n ..\dist\DownloadGuard-Desktop-*.jar 2^>nul') do if not defined JAR set "JAR=..\dist\%%f"

if not defined JAR (
  echo.
  echo   [缺少程序文件] 本目录下没有找到 DownloadGuard-Desktop-*.jar
  echo                   请把 jar 和本脚本放在同一个文件夹里。
  echo.
  pause
  exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo   [需要 Java] 没有找到 java，请先安装 Java 11 或更高版本：
  echo               https://adoptium.net/
  echo.
  pause
  exit /b 1
)

echo.
echo   正在启动 %JAR%
echo   浏览器会自动打开；关闭本窗口或按 Ctrl+C 退出。
echo.
java -jar "%JAR%" %*

if errorlevel 1 (
  echo.
  echo   [启动失败] 请把上面的错误信息反馈给开发者。
  pause
)
