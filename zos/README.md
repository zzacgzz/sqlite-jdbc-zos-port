# sqlite-jdbc on z/OS

This fork adds IBM z/OS (UNIX System Services, 64-bit JVM) to sqlite-jdbc. The driver
already maps `os.name` "z/OS" to `org/sqlite/native/zOS/s390x/`; what was missing is a
`libsqlitejdbc.so` built for z/OS and two small source fixes. No Java code changes.

Verified for driver 3.53.4.0 on IBM Semeru Java 21 (46/46 JDBC checks, JPA through
Hibernate 6.6). Only the route in section 2 is verified.

```
zos/
├── README.md               this file
├── build.sh                z/OS: compile and link libsqlitejdbc.so with xlc
├── zos-symlink-patch.pl    build-time edit of sqlite3.c (used by the Makefile)
├── Probe.java              quick smoke test
├── test/                   JDBC + JPA test suite (separate Maven project)
└── UPSTREAM_PR.md          draft PR text for the parts that could go upstream
```

## 1. What the port changes, and why

| Change | Where | Why |
|---|---|---|
| `cclass` renamed to `collationClass` | `src/main/java/org/sqlite/core/NativeDB.c` | z/OS `<collate.h>` declares `int cclass(char*, unsigned short**)`, which clashes with the static variable (xlc error CCN3343) |
| `$`-prefixed symlinks left to the kernel (`#ifdef __MVS__`) | amalgamation `sqlite3.c`, edited at build time by `zos/zos-symlink-patch.pl` | SQLite expands symlinks itself; `/tmp -> $SYSNAME/tmp` resolves only in the kernel, so `/tmp/x.db` failed with `SQLITE_CANTOPEN`. The edit fails the build if its anchor line is not found exactly once |
| `-q64 -qascii -qfloat=ieee -qlanglvl=extc99 -qdll -qexportall -O2` | compile flags | 64-bit JVM; JNI strings and SQL text are ASCII/UTF-8; Java and SQLite need IEEE doubles (xlc defaults to hex float); build a DLL with exports |
| `-D_ALL_SOURCE -D_XOPEN_SOURCE=600 -D_OPEN_SYS_FILE_EXT=1 -D_UNIX03_THREADS` | compile flags | POSIX headers on USS |
| `-DSQLITE_ASCII=1 -DSQLITE_BYTEORDER=4321 -DSQLITE_MAX_MMAP_SIZE=0` | SQLite flags | pin ASCII mode and big-endian byte order; no mmap for database I/O (WAL `-shm` mapping still works) |
| no `-DSQLITE_ENABLE_UPDATE_DELETE_LIMIT` | SQLite flags | only valid with an amalgamation built from SQLite's full source tree |
| `xlc -q64 -Wl,dll -o libsqlitejdbc.so NativeDB.o sqlite3.o` | link | produces the DLL plus the side deck `libsqlitejdbc.x` |
| `xlc -F $XLC_CFG` when `XLC_CFG` is set | build | on some systems the default `xlc.cfg` cannot find CCNDRVR |

All other SQLite options are exactly upstream's (the `$(SQLITE_OUT)/sqlite3.o` rule in
`Makefile`). The flags live in two places that must stay in sync: the `zOS-s390x` target in
`Makefile.common` and `zos/build.sh`.

## 2. Building the native library (supported route)

The sources are prepared on Windows (or any machine with make, perl, curl, tar and a JDK),
compiled on z/OS, and the library comes back into this repo so a normal `mvn package` ships it.

### 2.1 Windows (MSYS2 UCRT64): prepare the sources package

```bash
pacman -S --needed make perl curl tar unzip      # once
export JAVA_HOME=/c/path/to/jdk-21               # the Makefile calls $JAVA_HOME/bin/javac
make zos-sources
```

This downloads the SQLite amalgamation for the version in `VERSION`, applies upstream's edits
plus the symlink guard (the same Makefile rule the other platforms use), generates `NativeDB.h`
with `javac -h`, and writes `target/zos-build/` and `target/zos-build.tar`. It ends with these
checks and fails if any is off:

```
RegisterExtensionFunctions: 3 (want >= 2)
JDBC_EXTENSIONS:            1 (want 1)
ZOS_SYMLINK_PATCH:          1 (want 1)
collationClass:             6 (want 6)
Wrote target/zos-build.tar (NNNNNNNN bytes). ...
```

Write down the byte size it prints. The package contains `sqlite3.c`, `sqlite3.h`,
`sqlite3ext.h`, `NativeDB.c`, `NativeDB.h` and `build.sh`. It uses the plain amalgamation zip
with `UPDATE/DELETE LIMIT` off and never runs SQLite's `configure`.

**Offline or blocked networks.** sqlite.org and Maven Central may be blocked. Pre-download:

- the amalgamation: save `sqlite-amalgamation-<NUM>.zip` (for 3.53.4, `NUM` is `3530400`) as
  `target/amal.zip`, or as `target/sqlite-<version>-amal.zip`; or unpack it anywhere and pass
  `make zos-sources SQLITE_SOURCE=/path/to/sqlite-amalgamation-<NUM>`;
- `slf4j-api-1.7.36.jar` (needed by `javac -h`): save it as `target/classpath/slf4j-api.jar`.

### 2.2 z/OS (USS): compile

Upload `target/zos-build.tar` with sftp in binary mode and check its size with `ls -l`. Then:

```sh
pax -rf zos-build.tar -o from=ISO8859-1,to=IBM-1047
cd zos-build && chtag -tc IBM-1047 * && chmod +x build.sh
export XLC_CFG=/u/<you>/xlc.cfg     # only if the default xlc.cfg cannot find CCNDRVR
./build.sh                          # ./build.sh skip-sqlite reuses sqlite3.o
```

`build.sh` uses `JAVA_HOME` (default `/usr/lpp/java/J21.0_64`), prints the last lines of the
log if a step fails, and ends with `ls -l libsqlitejdbc.so libsqlitejdbc.x` and
`JNI exports: 61`. Then:

```sh
cksum libsqlitejdbc.so
```

### 2.3 Back on Windows: ship it in the jar

Download `libsqlitejdbc.so` with sftp in binary mode, check that `cksum` and the size match
the z/OS values, and put it at:

```
src/main/resources/org/sqlite/native/zOS/s390x/libsqlitejdbc.so
```

Then `mvn -DskipTests package`. The default jar includes every native folder except Android:

```bash
jar tf target/sqlite-jdbc-*.jar | grep zOS
# org/sqlite/native/zOS/s390x/libsqlitejdbc.so
```

The driver extracts the library to `java.io.tmpdir` (override with `-Dorg.sqlite.tmpdir=DIR`)
and loads it. With a stock jar instead, z/OS fails with
`NativeLibraryNotFoundException ... os.name=zOS, os.arch=s390x`; as a fallback you can point
the stock jar at a library on disk with
`-Dorg.sqlite.lib.path=DIR -Dorg.sqlite.lib.name=libsqlitejdbc.so`. (The old kit's
`bundle-jar.sh`, which added the `.so` to a copy of the stock jar, is no longer needed.)

### 2.4 New SQLite version

Update the fork to the new upstream release (which updates `VERSION`), then repeat 2.1 to 2.3.
If SQLite changes the code around `zLnk[got] = 0;`, the symlink edit stops the build with
`expected exactly 1 match ... not patched`; adjust `zos/zos-symlink-patch.pl` then.

## 3. Experimental: `make native` on z/OS

`Makefile.common` has a `zOS-s390x` target, so with GNU make, Perl and curl on USS (for example
from zopen) this might work directly on z/OS:

```sh
export XLC_CFG=/u/<you>/xlc.cfg   # if needed
make native                       # OS_NAME=zOS OS_ARCH=s390x are detected by OSInfo
```

This is **not verified**. Known differences from `build.sh`: the sources are not converted to
EBCDIC by `pax` first, and the link step also receives the compile flags. To see
the commands without running them on another machine:
`make -n native OS_NAME=zOS OS_ARCH=s390x`.

## 4. Tests

- `java -cp sqlite-jdbc-3.53.4.0.jar Probe.java /tmp/probe.db` is a 30-second smoke test:
  SQLite version, IEEE float, UTF-8 round trip, compile options, file I/O on zFS. Upload
  `Probe.java` as a file (sftp, binary); it contains non-ASCII text.
- `zos/test/` is the full suite (46 JDBC, 12 JPA checks). Build it with Maven on Windows and
  run it on z/OS with only a JDK. See [`test/README.md`](test/README.md).

## 5. Troubleshooting

| Problem | What to do |
|---|---|
| Text copied out of a chat or web viewer lost every `$` | Move files as files (download, sftp), never as copied text. Compare byte sizes with `wc -c` |
| A heredoc pasted into a PuTTY session broke long lines | Don't paste scripts into a terminal; transfer the file |
| A new upload drops the file tag (`ls -T`) | After uploading an ASCII script: `chtag -tc ISO8859-1 <file>`. Sources unpacked with `pax ... to=IBM-1047` are EBCDIC: `chtag -tc IBM-1047`. Check with `od -x` (ASCII `#!` is `2321`, EBCDIC is `7b5a`) |
| `sed` or redirection on z/OS wrote ASCII into a file tagged EBCDIC | After editing on z/OS, check the first bytes and retag. Prefer making edits on Windows |
| The default `xlc.cfg` cannot spawn `/usr/lpp/cbclib/xlc/exe/ccndrvr` | `export XLC_CFG=/u/<you>/xlc.cfg` (passed as `xlc -F`) |
| `grep` on `libsqlitejdbc.x` says lines are truncated | The side deck is 80-byte records without newlines: `fold -w 80 libsqlitejdbc.x \| grep -c Java_org_sqlite` (61) |
| `NativeLibraryNotFoundException os.name=zOS, os.arch=s390x` | The jar has no z/OS library: use the jar from section 2.3, or `-Dorg.sqlite.lib.path` |
| Hibernate startup stalls for minutes on z/OS | `hibernate.archive.scanner=org.hibernate.boot.archive.scan.internal.DisabledScanner` and `hibernate.archive.autodetection=none`, with entities listed explicitly. Heap size was not the cause |
| `persistence.xml` fails to parse | XML comments must not contain `--`. Validate XML with a parser before committing |
| The terminal shows `ñ` as boxes | Display only. Check the data with SQL `hex()` (`ñandú` is `C3B1616E64C3BA`) |
| `make zos-sources` stops at tar with `Permission denied` (Windows) | Use the tar that ships with Windows: `make zos-sources TAR=/c/Windows/System32/tar.exe` |
| Files under `/tmp` fail with `SQLITE_CANTOPEN` | The library was built without the symlink edit; `build.sh` warns about this. Rebuild from `make zos-sources` |

Open question: a build was once seen failing because "a Maven plugin looks for a specific
header file". Nothing in this repository's `pom.xml` reads a header. The header dependencies
are in `make`: `jni-header` (`javac -h`, which first downloads `slf4j-api.jar` from Maven
Central) and `Makefile.common` (finds `jni.h` under `$JAVA_HOME`). If it happens again, note
the exact command and plugin name.
