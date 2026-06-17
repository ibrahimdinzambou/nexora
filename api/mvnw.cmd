@echo off
setlocal
set "BASE_DIR=%~dp0"
set "MAVEN_VERSION=3.9.10"
set "MAVEN_HOME=%BASE_DIR%.mvn\apache-maven-%MAVEN_VERSION%"
set "MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo Downloading Apache Maven %MAVEN_VERSION%...
  if not exist "%BASE_DIR%.mvn" mkdir "%BASE_DIR%.mvn"
  curl.exe -L --fail --output "%BASE_DIR%.mvn\apache-maven-%MAVEN_VERSION%-bin.zip" "%MAVEN_URL%"
  if errorlevel 1 exit /b 1
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -LiteralPath '%BASE_DIR%.mvn\apache-maven-%MAVEN_VERSION%-bin.zip' -DestinationPath '%BASE_DIR%.mvn' -Force"
  if errorlevel 1 exit /b 1
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
