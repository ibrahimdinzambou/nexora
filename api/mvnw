#!/usr/bin/env sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MAVEN_VERSION=3.9.10
MAVEN_HOME="$BASE_DIR/.mvn/apache-maven-$MAVEN_VERSION"
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  echo "Downloading Apache Maven $MAVEN_VERSION..."
  mkdir -p "$BASE_DIR/.mvn"
  curl -fsSL "$MAVEN_URL" | tar -xz -C "$BASE_DIR/.mvn"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
