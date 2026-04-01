#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_DIR="$BASE_DIR/.mvn/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/maven-wrapper.jar"
WRAPPER_PROPERTIES="$WRAPPER_DIR/maven-wrapper.properties"
DOWNLOADER_JAVA="$WRAPPER_DIR/MavenWrapperDownloader.java"
DOWNLOADER_CLASS="$WRAPPER_DIR/MavenWrapperDownloader.class"

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_EXE="${JAVA_HOME}/bin/java"
else
  JAVA_EXE="java"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/javac" ]; then
  JAVAC_EXE="${JAVA_HOME}/bin/javac"
else
  JAVAC_EXE="javac"
fi

if [ ! -f "$WRAPPER_JAR" ]; then
  if [ ! -f "$DOWNLOADER_JAVA" ]; then
    echo "Missing $DOWNLOADER_JAVA" >&2
    exit 1
  fi
  "$JAVAC_EXE" "$DOWNLOADER_JAVA"
  "$JAVA_EXE" -cp "$WRAPPER_DIR" MavenWrapperDownloader "$WRAPPER_DIR"
fi

exec "$JAVA_EXE" \
  -Dmaven.multiModuleProjectDirectory="$BASE_DIR" \
  -classpath "$WRAPPER_JAR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
