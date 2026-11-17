@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
set "JAR=DownloadGuard-Desktop-3.0.0.jar"

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
echo   DownloadGuard Desktop 3.0.0
echo   正在启动，浏览器会自动打开；关闭本窗口或按 Ctrl+C 退出。
echo.
java -jar "%JAR%" %*

if errorlevel 1 (
  echo.
  echo   [启动失败] 请把上面的错误信息反馈给开发者。
  pause
)
