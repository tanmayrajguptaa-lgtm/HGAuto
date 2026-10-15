@echo off
REM Build HGAuto 1.2.0. Requires JDK 25 and Maven installed.
cd /d "%~dp0"
java -version
mvn -version
if errorlevel 1 exit /b 1
mvn clean package
if errorlevel 1 exit /b 1
echo Built target\hgauto-1.2.0.jar
