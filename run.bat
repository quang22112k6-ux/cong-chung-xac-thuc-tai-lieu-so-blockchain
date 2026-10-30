@echo off
chcp 65001 >nul
REM Bien dich va chay Blockchain Notary (can JDK 17 tro len).
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 -d out -sourcepath src\main\java src\main\java\vn\edu\notary\Main.java
if errorlevel 1 (
  echo.
  echo Bien dich that bai. Hay kiem tra da cai JDK 17 tro len: go "javac -version"
  pause
  exit /b 1
)
xcopy /e /i /y src\main\resources out >nul
echo Dang khoi dong... Mo trinh duyet tai http://127.0.0.1:8080
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out vn.edu.notary.Main %*
pause
