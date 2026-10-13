# sqlite-jdbc z/OS test suite

A test suite for the z/OS port of [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc). It contains:

- **46 JDBC checks** covering native loading, IEEE floats, 64-bit integers, UTF-8, binary data, compiled-in SQLite features, the JDBC API, JNI callbacks, and file locking.
- **12 JPA checks** run through Hibernate 6 with the community `SQLiteDialect`.
Building the native library is described in [`../README.md`](../README.md); the build scripts live in `zos/`.

Nothing here needs Maven on z/OS. Only `java` runs there.

```
zos/test/
├── run.sh                         runs the suite (z/OS, Linux, MSYS2)
├── pom.xml                        builds target/sqlite-zos-test.jar (JDBC + JPA)
├── src/main/java/sqlitezos/       Main, Report (harness), JdbcSuite
├── src/main/java/sqlitezos/jpa/   JpaSuite, Author, Book entities
└── src/main/resources/META-INF/persistence.xml
```

This is a separate Maven project, not a module of the root build.

---

## 1. Build the test jar once on Windows

Building needs Maven with access to Maven Central or your company mirror.

```bash
cd zos/test
mvn -q package             # -> target/sqlite-zos-test.jar (about 15 MB, Hibernate included)
```

You can try the suite on Windows first, from MSYS2, against the jar built by the root project
(`mvn -DskipTests package` in the repo root) or a stock driver jar:

```bash
SQLITE_JAR=../../target/sqlite-jdbc-3.53.4.0.jar ./run.sh --dir "$TEMP"
```

## 2. Run on z/OS

Upload with sftp (binary transfer) into one folder on USS, for example `/u/alecg/sqlite-zos-test`:
`zos/test/run.sh`, `zos/test/target/sqlite-zos-test.jar`, and the driver jar
(`target/sqlite-jdbc-3.53.4.0.jar` from the root build, which contains the z/OS library). Then:

```sh
cd /u/alecg/sqlite-zos-test
chtag -tc ISO8859-1 run.sh
chmod +x run.sh

export JAVA_HOME=/usr/lpp/java/J21.0_64
./run.sh --dir /tmp
```

`run.sh` looks for `sqlite-jdbc-*-zos.jar`, then `sqlite-jdbc-*.jar`, in the same folder and in the parent folder. To point it somewhere else, set `SQLITE_JAR=/path/to/jar`.

To test the library in your build folder without bundling it into the jar:

```sh
SQLITE_JAR=../sqlite-jdbc-3.53.4.0.jar SQLITE_LIB_DIR=../zos-build ./run.sh
```

## 3. Options and output

| Option | Meaning |
|---|---|
| `--dir PATH` | where test databases are created (default `/tmp`). `/tmp` is a `$SYSNAME` symlink on z/OS, so the default also tests the symlink fix |
| `--jdbc-only` / `--jpa-only` | run one suite |
| `--keep` | keep the `zos-*.db` files for inspection |
| `--verbose` | print stack traces for failures |

| Environment variable | Meaning |
|---|---|
| `SQLITE_JAR` | driver jar to test |
| `SQLITE_LIB_DIR` | load `libsqlitejdbc.so` from this folder (`-Dorg.sqlite.lib.path`) |
| `TEST_JAR` | test jar to use |
| `JAVA_HOME`, `JAVA_OPTS` | JDK and extra JVM options |

Each check prints `[PASS]`, `[FAIL]` or `[SKIP]`. Each one runs with a timeout, so a hang in native code shows up as a failure instead of freezing the run. The run ends with `RESULT: ALL CHECKS PASSED`. The exit code is `0` if everything passed, `1` if anything failed, and `2` for bad usage.

## 4. What a failure points to

| Section | If it fails, look at |
|---|---|
| driver and native library | the `.so` isn't found or doesn't load (path, `org/sqlite/native/zOS/s390x/`, `-Dorg.sqlite.tmpdir` not writable) |
| numbers | `-qfloat=ieee` missing: hex floating point gives wrong doubles |
| text and character encoding | `-qascii` or `SQLITE_ASCII` missing, or something converting at the JNI boundary |
| binary data | bytes being translated (should never happen) |
| compiled-in features | a `-DSQLITE_ENABLE_*` flag missing from `build.sh`, or the sqlite-jdbc patches not applied |
| JDBC API | driver/engine mismatch (`NativeDB.c` and the jar must be the same version) |
| Java callbacks (JNI) | `FindClass` or method-ID lookups. Collation uses the renamed `collationClass` |
| files and locking | zFS: fcntl locks, WAL shared memory (`-shm` uses `mmap`), the symlink patch |
| JPA | Hibernate or dialect setup. Run `--jdbc-only` first: if JDBC passes, the port is fine |

## 5. Rebuilding the native library (new SQLite version)

See [`../README.md`](../README.md): `make zos-sources` on Windows, `zos/build.sh` on z/OS, then the
library goes into the root project's resources so `mvn package` ships it. Run this suite against the
resulting jar.

## 6. What the port changes, and why

See the table in [`../README.md`](../README.md).

## 7. Using it from your own app

- **JDBC:** put `sqlite-jdbc-3.53.4.0-zos.jar` on the classpath in place of the stock jar.
- **JPA:** add `hibernate-community-dialects` (same version as `hibernate-core`) and set the dialect to `org.hibernate.community.dialect.SQLiteDialect`. Use `GenerationType.IDENTITY`.
- **Schema:** SQLite's `ALTER TABLE` is limited, so don't rely on `ddl-auto=update` beyond adding columns.
- **Concurrency:** keep one writer at a time. Add `?busy_timeout=10000` to the URL, and use one LPAR per database file.
