#!/bin/sh
# Runs the sqlite-jdbc test suite. Works on z/OS USS, Linux and MSYS2.
#
#   ./run.sh [options]       e.g.  ./run.sh --dir /tmp --verbose
#
# Suite options: --dir PATH  --jdbc-only  --jpa-only  --keep  --verbose
#
# Environment (all optional):
#   SQLITE_JAR      sqlite-jdbc jar to test. Default: the first
#                   sqlite-jdbc-*-zos.jar, then sqlite-jdbc-*.jar, found in
#                   this folder or its parent.
#   SQLITE_LIB_DIR  load libsqlitejdbc.so from this folder instead of
#                   from inside the jar (e.g. your zos-build folder).
#   TEST_JAR        test jar. Default: target/sqlite-zos-test.jar or
#                   sqlite-zos-test.jar (full, with JPA), else
#                   dist/sqlite-zos-jdbc-test.jar (JDBC only).
#   JAVA_HOME       JDK to use (else java from PATH).
#   JAVA_OPTS       extra JVM options.

HERE=$(cd "$(dirname "$0")" && pwd)

first_file() {
  for f in "$@"; do
    if [ -f "$f" ]; then
      echo "$f"
      return 0
    fi
  done
  return 1
}

if [ -z "$TEST_JAR" ]; then
  TEST_JAR=$(first_file \
    "$HERE/target/sqlite-zos-test.jar" \
    "$HERE/sqlite-zos-test.jar" \
    "$HERE/dist/sqlite-zos-jdbc-test.jar")
fi
if [ -z "$TEST_JAR" ] || [ ! -f "$TEST_JAR" ]; then
  echo "run.sh: no test jar found (set TEST_JAR)" >&2
  exit 2
fi

if [ -z "$SQLITE_JAR" ]; then
  SQLITE_JAR=$(first_file \
    "$HERE"/sqlite-jdbc-*-zos.jar \
    "$HERE"/../sqlite-jdbc-*-zos.jar \
    "$HERE"/sqlite-jdbc-*.jar \
    "$HERE"/../sqlite-jdbc-*.jar)
fi
if [ -z "$SQLITE_JAR" ] || [ ! -f "$SQLITE_JAR" ]; then
  echo "run.sh: no sqlite-jdbc jar found (set SQLITE_JAR)" >&2
  exit 2
fi

JAVA=java
if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
fi

LIBOPTS=""
if [ -n "$SQLITE_LIB_DIR" ]; then
  LIBOPTS="-Dorg.sqlite.lib.path=$SQLITE_LIB_DIR"
  LIBOPTS="$LIBOPTS -Dorg.sqlite.lib.name=libsqlitejdbc.so"
fi

# Windows java needs ';' and Windows paths when run from MSYS2/Git Bash.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    CP="$(cygpath -w "$TEST_JAR");$(cygpath -w "$SQLITE_JAR")"
    ;;
  *)
    CP="$TEST_JAR:$SQLITE_JAR"
    ;;
esac

echo "test jar   : $TEST_JAR"
echo "sqlite jar : $SQLITE_JAR"
[ -n "$SQLITE_LIB_DIR" ] && echo "native lib : $SQLITE_LIB_DIR/libsqlitejdbc.so"
echo

# shellcheck disable=SC2086
exec "$JAVA" $JAVA_OPTS $LIBOPTS -cp "$CP" sqlitezos.Main "$@"
