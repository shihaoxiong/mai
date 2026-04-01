@ECHO OFF
SETLOCAL

SET BASE_DIR=%~dp0
SET WRAPPER_DIR=%BASE_DIR%\.mvn\wrapper
SET WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar
SET DOWNLOADER_JAVA=%WRAPPER_DIR%\MavenWrapperDownloader.java

IF DEFINED JAVA_HOME (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
  SET JAVAC_EXE=%JAVA_HOME%\bin\javac.exe
) ELSE (
  SET JAVA_EXE=java
  SET JAVAC_EXE=javac
)

IF NOT EXIST "%WRAPPER_JAR%" (
  "%JAVAC_EXE%" "%DOWNLOADER_JAVA%"
  "%JAVA_EXE%" -cp "%WRAPPER_DIR%" MavenWrapperDownloader "%WRAPPER_DIR%"
)

"%JAVA_EXE%" -Dmaven.multiModuleProjectDirectory="%BASE_DIR%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
