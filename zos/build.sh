#!/bin/sh
# Step 2 of the z/OS port - runs on z/OS USS inside zos-build/.
# Builds libsqlitejdbc.so (64-bit, ASCII, IEEE float) with xlc.
#
#   ./build.sh              full build (sqlite3.c takes a while)
#   ./build.sh skip-sqlite  reuse sqlite3.o, rebuild NativeDB.c + link
#
# Environment:
#   JAVA_HOME  64-bit JDK (default /usr/lpp/java/J21.0_64)
#   XLC_CFG    custom xlc config file, passed as  xlc -F $XLC_CFG
#              (needed when the default xlc.cfg cannot find CCNDRVR)
set -e
cd "$(dirname "$0")"

: "${JAVA_HOME:=/usr/lpp/java/J21.0_64}"
JNIMD=$(find "$JAVA_HOME/include" -name jni_md.h | head -1)
if [ -z "$JNIMD" ]; then
  echo "jni_md.h not found under $JAVA_HOME/include - set JAVA_HOME"
  exit 1
fi
JNIMD_DIR=$(dirname "$JNIMD")

XLC="xlc"
if [ -n "$XLC_CFG" ]; then
  XLC="xlc -F $XLC_CFG"
fi

echo "JAVA_HOME=$JAVA_HOME"
echo "jni_md.h in $JNIMD_DIR"
echo "compiler: $XLC"

# Sanity checks on the prepared sources
grep -q ZOS_SYMLINK_PATCH sqlite3.c || {
  echo "WARNING: sqlite3.c lacks ZOS_SYMLINK_PATCH - files under /tmp"
  echo "         (\$SYSNAME symlinks) will fail with SQLITE_CANTOPEN"
}
if grep -q 'jclass  *cclass' NativeDB.c; then
  echo "NativeDB.c still uses 'cclass' (clashes with collate.h) - fixing"
  sed 's/cclass/collationClass/g' NativeDB.c > NativeDB.c.new
  mv NativeDB.c.new NativeDB.c
fi

# Keep CFLAGS and S in sync with the zOS-s390x target in Makefile.common
# (zOS-s390x_CCFLAGS, zOS-s390x_SQLITE_FLAGS) and the shared sqlite3.o rule.
# -qascii       strings are ASCII/UTF-8, as JNI and SQLite expect
# -qfloat=ieee  IEEE 754 doubles, as Java and SQLite expect
CFLAGS="-q64 -qascii -qfloat=ieee"
CFLAGS="$CFLAGS -qlanglvl=extc99"
CFLAGS="$CFLAGS -qdll -qexportall -O2"
CFLAGS="$CFLAGS -D_ALL_SOURCE"
CFLAGS="$CFLAGS -D_XOPEN_SOURCE=600"
CFLAGS="$CFLAGS -D_OPEN_SYS_FILE_EXT=1"
CFLAGS="$CFLAGS -D_UNIX03_THREADS"
CFLAGS="$CFLAGS -I. -I$JAVA_HOME/include"
CFLAGS="$CFLAGS -I$JNIMD_DIR"

# Same SQLite options as upstream's Makefile, plus:
#   SQLITE_ASCII, SQLITE_BYTEORDER=4321  (pin ASCII mode, big-endian)
#   SQLITE_MAX_MMAP_SIZE=0               (no mmap for database I/O)
S="-DSQLITE_ASCII=1"
S="$S -DSQLITE_BYTEORDER=4321"
S="$S -DSQLITE_ENABLE_LOAD_EXTENSION=1"
S="$S -DSQLITE_HAVE_ISNAN"
S="$S -DHAVE_USLEEP=1"
S="$S -DSQLITE_ENABLE_COLUMN_METADATA"
S="$S -DSQLITE_CORE"
S="$S -DSQLITE_ENABLE_FTS3"
S="$S -DSQLITE_ENABLE_FTS3_PARENTHESIS"
S="$S -DSQLITE_ENABLE_FTS5"
S="$S -DSQLITE_ENABLE_RTREE"
S="$S -DSQLITE_ENABLE_PERCENTILE"
S="$S -DSQLITE_ENABLE_STAT4"
S="$S -DSQLITE_ENABLE_DBSTAT_VTAB"
S="$S -DSQLITE_ENABLE_MATH_FUNCTIONS"
S="$S -DSQLITE_THREADSAFE=1"
S="$S -DSQLITE_DEFAULT_MEMSTATUS=0"
S="$S -DSQLITE_DEFAULT_FILE_PERMISSIONS=0666"
S="$S -DSQLITE_MAX_VARIABLE_NUMBER=250000"
S="$S -DSQLITE_MAX_MMAP_SIZE=0"
S="$S -DSQLITE_MAX_LENGTH=2147483647"
S="$S -DSQLITE_MAX_COLUMN=32767"
S="$S -DSQLITE_MAX_SQL_LENGTH=1073741824"
S="$S -DSQLITE_MAX_FUNCTION_ARG=127"
S="$S -DSQLITE_MAX_ATTACHED=125"
S="$S -DSQLITE_MAX_PAGE_COUNT=4294967294"
S="$S -DSQLITE_DISABLE_PAGECACHE_OVERFLOW_STATS"

fail() {
  echo "FAILED - last lines of $1:"
  tail -30 "$1"
  exit 1
}

echo "== compiling NativeDB.c"
$XLC $CFLAGS -c NativeDB.c -o NativeDB.o \
  > nativedb.log 2>&1 || fail nativedb.log

if [ "$1" != "skip-sqlite" ]; then
  echo "== compiling sqlite3.c (slow)"
  $XLC $CFLAGS $S -c sqlite3.c -o sqlite3.o \
    > sqlite3.log 2>&1 || fail sqlite3.log
fi

echo "== linking"
$XLC -q64 -Wl,dll -o libsqlitejdbc.so \
  NativeDB.o sqlite3.o > link.log 2>&1 || fail link.log

ls -l libsqlitejdbc.so libsqlitejdbc.x
# the side deck is fixed 80-byte records without newlines
N=$(fold -w 80 libsqlitejdbc.x | grep -c Java_org_sqlite)
echo "JNI exports: $N"
