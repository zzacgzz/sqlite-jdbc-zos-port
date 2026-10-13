# Draft PR for xerial/sqlite-jdbc (not opened)

Commits to include, cherry-picked from `zos-port` in this order (they apply cleanly to
upstream `master` as of 49e5c184):

- `fix(native): rename cclass to avoid clash with z/OS collate.h`
- `fix(native): leave $-prefixed symlinks to the z/OS kernel` (brings `zos/zos-symlink-patch.pl`)
- `build(zos): add zOS-s390x target to Makefile.common`

Leave out: the z/OS binary, `zos/test/`, `zos/build.sh`, `zos/Probe.java`, `make zos-sources`,
`.gitattributes` and the fork-only docs. If upstream prefers, the patch script can move to
another folder (for example `lib/` or `scripts/`); only the Makefile line refers to it.

---

**Title:** `feat: build support for z/OS (s390x)`

**Body:**

This makes the native library buildable on IBM z/OS (UNIX System Services, 64-bit) with
IBM xlc. `OSInfo` already maps `os.name` "z/OS" to `zOS/s390x`, so no Java change is needed.
All changes are inert on other platforms.

- **`NativeDB.c`: rename the static `cclass` to `collationClass`.** z/OS `<collate.h>` declares
  `int cclass(char*, unsigned short**)`, and xlc rejects the redeclaration (CCN3343). Pure rename,
  8 uses on 6 lines.
- **Amalgamation: leave `$`-prefixed symlinks to the kernel.** SQLite's unix VFS expands
  symlinks itself (`appendOnePathElement`). z/OS system links such as `/tmp -> $SYSNAME/tmp`
  contain a symbol only the kernel substitutes, so opening `/tmp/x.db` failed with
  `SQLITE_CANTOPEN`. A build-time edit, next to the existing Perl edits, inserts
  `#ifdef __MVS__ if( zLnk[0]=='$' ) return; #endif` after `zLnk[got] = 0;`. The script fails
  the build if that line is not found exactly once, so a future SQLite change cannot silently
  drop the guard.
- **`Makefile.common`: `zOS-s390x` target** (xlc, `-q64 -qascii -qfloat=ieee -qdll -qexportall`,
  `SQLITE_ASCII`, `SQLITE_BYTEORDER=4321`, no strip). For this target
  `ENABLE_UPDATE_DELETE_LIMIT` defaults to 0 and `SQLITE_MAX_MMAP_SIZE` to 0. To set the latter
  without relying on the order of two `-D` options, `SQLITE_MAX_MMAP_SIZE` becomes a make
  variable whose default (`1099511627776`) is unchanged for every other target.

Testing: built on z/OS with xlc (USS, IBM Semeru Java 21). A JDBC suite of 46 checks (native
loading, IEEE floats, UTF-8, JNI callbacks including collations, file locking and WAL on zFS)
and a JPA suite through Hibernate 6.6 pass. On other platforms the output is unchanged apart
from the inert `#ifdef __MVS__` block in the patched `sqlite3.c`.

No z/OS binary is included: building it needs a z/OS system, so it cannot be part of the
Docker cross-compile matrix.
