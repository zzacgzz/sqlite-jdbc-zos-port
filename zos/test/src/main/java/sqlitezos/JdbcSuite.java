package sqlitezos;

import static sqlitezos.Report.eq;
import static sqlitezos.Report.eqBits;
import static sqlitezos.Report.expectThrows;
import static sqlitezos.Report.that;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.sqlite.BusyHandler;
import org.sqlite.Collation;
import org.sqlite.Function;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteCommitListener;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteJDBCLoader;
import org.sqlite.SQLiteUpdateListener;
import org.sqlite.util.OSInfo;

/**
 * Plain JDBC checks. Grouped so a failure points at the layer that broke:
 * native loading, float/int representation, text encoding, binary data,
 * compiled-in SQLite features, JDBC API, Java callbacks through JNI, and
 * file I/O / locking on the target file system.
 */
public final class JdbcSuite implements Main.Suite {

  static final String EXPECTED_SQLITE = "3.53.4";

  /** n-tilde, a, n, d, u-acute: 2-byte UTF-8 sequences. */
  static final String NANDU = "ñandú";
  /** U+1F600 grinning face: 4-byte UTF-8 / Java surrogate pair. */
  static final String EMOJI = "😀";
  /** Euro, Japanese, Greek, Hebrew. */
  static final String MIXED = "€ 日本語 Ελλ"
      + "ηνικά עברית";

  private final String dir;
  private final boolean keep;
  private final Report r;
  private final List<String> created = new ArrayList<>();

  public JdbcSuite(String dir, boolean keep, Report r) {
    this.dir = dir;
    this.keep = keep;
    this.r = r;
  }

  // ------------------------------------------------------------- helpers

  private String path(String name) {
    String p = new File(dir, name).getPath();
    created.add(p);
    delete(p);
    return p;
  }

  private static void delete(String p) {
    for (String s : new String[] {"", "-journal", "-wal", "-shm"}) {
      new File(p + s).delete();
    }
  }

  static Connection mem() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite::memory:");
  }

  static Connection open(String file) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + file);
  }

  static Connection open(String file, SQLiteConfig cfg) throws SQLException {
    return cfg.createConnection("jdbc:sqlite:" + file);
  }

  static String one(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(sql)) {
      that(rs.next(), "no row from: " + sql);
      return rs.getString(1);
    }
  }

  static long longOf(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(sql)) {
      that(rs.next(), "no row from: " + sql);
      return rs.getLong(1);
    }
  }

  static void exec(Connection c, String... sql) throws SQLException {
    try (Statement s = c.createStatement()) {
      for (String q : sql) {
        s.executeUpdate(q);
      }
    }
  }

  static Object bindAndSelect(Connection c, Object v) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("select ?")) {
      p.setObject(1, v);
      try (ResultSet rs = p.executeQuery()) {
        rs.next();
        return rs.getObject(1);
      }
    }
  }

  static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) {
      sb.append(String.format("%02X", x & 0xff));
    }
    return sb.toString();
  }

  // ----------------------------------------------------------------- run

  @Override
  public void run() {
    driver();
    numbers();
    text();
    binary();
    features();
    jdbcApi();
    callbacks();
    files();
    if (!keep) {
      for (String p : created) {
        delete(p);
      }
    }
  }

  // ------------------------------------------------------------ sections

  private void driver() {
    r.section("JDBC: driver and native library");

    r.check("native library loads", () -> {
      Class.forName("org.sqlite.JDBC");
      that(SQLiteJDBCLoader.isNativeMode(),
          "SQLiteJDBCLoader.isNativeMode() is false");
      return "platform folder " + OSInfo.getNativeLibFolderPathForCurrentOS();
    });

    r.check("sqlite_version() = " + EXPECTED_SQLITE, () -> {
      try (Connection c = mem()) {
        String v = one(c, "select sqlite_version()");
        eq(EXPECTED_SQLITE, v);
        return v;
      }
    });

    r.check("sqlite-jdbc amalgamation patches present", () -> {
      try (Connection c = mem()) {
        eq("1", one(c,
            "select sqlite_compileoption_used('JDBC_EXTENSIONS')"));
        eq("1", one(c,
            "select sqlite_compileoption_used('ENABLE_COLUMN_METADATA')"));
        eq("1", one(c, "select sqlite_compileoption_used('THREADSAFE=1')"));
        return "JDBC_EXTENSIONS, COLUMN_METADATA, THREADSAFE=1";
      }
    });
  }

  private void numbers() {
    r.section("JDBC: numbers (IEEE 754 doubles, 64-bit integers)");

    r.check("double arithmetic in SQL", () -> {
      try (Connection c = mem();
           Statement s = c.createStatement();
           ResultSet rs = s.executeQuery(
               "select 1.5 * 2.25, 0.1 + 0.2, 2.0 / 3, -1e300 * 1e-300")) {
        rs.next();
        eqBits(3.375, rs.getDouble(1));
        eqBits(0.1 + 0.2, rs.getDouble(2));
        eqBits(2.0 / 3, rs.getDouble(3));
        eqBits(-1e300 * 1e-300, rs.getDouble(4));
        return "3.375, 0.30000000000000004, 0.666..., -1.0";
      }
    });

    r.check("double bind round trip is bit-exact", () -> {
      double[] vals = {Math.PI, -123456.789, 1.0 / 3, 1e-300,
          Double.MAX_VALUE, Double.MIN_NORMAL, Double.MIN_VALUE, 6.02214076e23};
      try (Connection c = mem();
           PreparedStatement p = c.prepareStatement("select ?")) {
        for (double v : vals) {
          p.setDouble(1, v);
          try (ResultSet rs = p.executeQuery()) {
            rs.next();
            eqBits(v, rs.getDouble(1));
          }
        }
      }
      return vals.length + " values incl. MAX, MIN_NORMAL, subnormal";
    });

    r.check("SQLite text/real conversions", () -> {
      try (Connection c = mem()) {
        eqBits(3.14159, Double.parseDouble(
            one(c, "select cast('3.14159' as real)")));
        eq("0.667", one(c, "select printf('%.3f', 2.0/3)"));
        eq("1.0e+100", one(c, "select printf('%.1e', 1e100)"));
        eq("real", one(c, "select typeof(1e308 * 10)"));
        return "cast, printf %f %e, overflow";
      }
    });

    r.check("64-bit integer round trip", () -> {
      long[] vals = {Long.MAX_VALUE, Long.MIN_VALUE, -1L, 0L,
          4294967296L, -4294967297L, 0x0102030405060708L};
      try (Connection c = mem();
           PreparedStatement p = c.prepareStatement("select ?, typeof(?)")) {
        for (long v : vals) {
          p.setLong(1, v);
          p.setLong(2, v);
          try (ResultSet rs = p.executeQuery()) {
            rs.next();
            eq(v, rs.getLong(1));
            eq("integer", rs.getString(2));
          }
        }
        eq(Long.MAX_VALUE, longOf(c, "select 9223372036854775807"));
        eq("7 / 2 = 3, -7 % 3 = -1",
            "7 / 2 = " + one(c, "select 7 / 2") + ", -7 % 3 = "
                + one(c, "select -7 % 3"));
        eq("real", one(c, "select typeof(9223372036854775807 + 1)"));
      }
      return "MAX, MIN, byte-order pattern 0x0102030405060708";
    });
  }

  private void text() {
    r.section("JDBC: text and character encoding");

    r.check("UTF-8 bytes stored for 2-byte characters", () -> {
      try (Connection c = mem();
           PreparedStatement p = c.prepareStatement(
               "select hex(?), length(?), ?")) {
        p.setString(1, NANDU);
        p.setString(2, NANDU);
        p.setString(3, NANDU);
        try (ResultSet rs = p.executeQuery()) {
          rs.next();
          eq("C3B1616E64C3BA", rs.getString(1));
          eq(5, rs.getInt(2));
          eq(NANDU, rs.getString(3));
        }
        return "hex C3B1616E64C3BA, length 5, round trip equal";
      }
    });

    r.check("4-byte characters (surrogate pairs)", () -> {
      try (Connection c = mem();
           PreparedStatement p = c.prepareStatement(
               "select hex(?), length(?), ?")) {
        p.setString(1, EMOJI);
        p.setString(2, EMOJI);
        p.setString(3, EMOJI);
        try (ResultSet rs = p.executeQuery()) {
          rs.next();
          eq("F09F9880", rs.getString(1));
          eq(1, rs.getInt(2));
          eq(EMOJI, rs.getString(3));
        }
        return "U+1F600 -> F09F9880";
      }
    });

    r.check("string literals inside SQL text", () -> {
      try (Connection c = mem()) {
        eq(NANDU, one(c, "select '" + NANDU + "'"));
        eq(MIXED, one(c, "select '" + MIXED + "'"));
        eq(MIXED + EMOJI, one(c, "select '" + MIXED + "' || '" + EMOJI + "'"));
        return "Spanish, Euro, Japanese, Greek, Hebrew, emoji";
      }
    });

    r.check("ASCII SQL functions and LIKE", () -> {
      try (Connection c = mem()) {
        eq("ABC", one(c, "select upper('abc')"));
        eq("abc", one(c, "select lower('ABC')"));
        eq("1", one(c, "select 'ABC' like 'abc'"));
        eq("1", one(c, "select 'zOS-port' glob 'z*-p?rt'"));
        eq("bc", one(c, "select substr('abc', 2)"));
        eq("4", one(c, "select instr('sqlite', 'i')"));
        eq("A", one(c, "select char(65)"));
        eq("65", one(c, "select unicode('A')"));
        return "upper, lower, like, glob, substr, instr, char, unicode";
      }
    });

    r.check("SQLite error messages are readable", () -> {
      try (Connection c = mem()) {
        Throwable t = expectThrows("syntax error",
            () -> exec(c, "selec 1"));
        String m = String.valueOf(t.getMessage());
        that(m.contains("syntax error"), "message was: " + m);
        Throwable t2 = expectThrows("no such table",
            () -> one(c, "select * from no_such_table"));
        that(String.valueOf(t2.getMessage()).contains("no such table"),
            "message was: " + t2.getMessage());
        return "\"syntax error\", \"no such table\"";
      }
    });

    r.check("1 MB mixed-script text round trip", () -> {
      StringBuilder sb = new StringBuilder();
      String unit = "abc " + NANDU + " " + MIXED + " " + EMOJI + "\n";
      while (sb.length() < 1_000_000) {
        sb.append(unit);
      }
      String big = sb.toString();
      int codePoints = big.codePointCount(0, big.length());
      try (Connection c = mem()) {
        exec(c, "create table t(x text)");
        try (PreparedStatement p = c.prepareStatement(
            "insert into t values (?)")) {
          p.setString(1, big);
          p.executeUpdate();
        }
        eq((long) codePoints, longOf(c, "select length(x) from t"));
        eq((long) big.getBytes(StandardCharsets.UTF_8).length,
            longOf(c, "select length(cast(x as blob)) from t"));
        eq(big, one(c, "select x from t"));
      }
      return codePoints + " characters";
    });
  }

  private void binary() {
    r.section("JDBC: binary data (must never be translated)");

    r.check("BLOB with all 256 byte values", () -> {
      byte[] all = new byte[256];
      for (int i = 0; i < 256; i++) {
        all[i] = (byte) i;
      }
      try (Connection c = mem();
           PreparedStatement p = c.prepareStatement(
               "select ?, hex(?), length(?)")) {
        p.setBytes(1, all);
        p.setBytes(2, all);
        p.setBytes(3, all);
        try (ResultSet rs = p.executeQuery()) {
          rs.next();
          that(Arrays.equals(all, rs.getBytes(1)), "bytes differ");
          eq(hex(all), rs.getString(2));
          eq(256, rs.getInt(3));
        }
      }
      return "0x00..0xFF identical";
    });

    r.check("BLOB literals, zeroblob, randomblob", () -> {
      try (Connection c = mem()) {
        eq("C1C2C3", one(c, "select hex(x'C1C2C3')"));
        eq("1000", one(c, "select length(zeroblob(1000))"));
        eq("16", one(c, "select length(randomblob(16))"));
        return "x'C1C2C3' stays C1C2C3";
      }
    });
  }

  private void features() {
    r.section("JDBC: compiled-in SQLite features");

    r.check("math functions", () -> {
      try (Connection c = mem()) {
        eqBits(4.0, Double.parseDouble(one(c, "select sqrt(16)")));
        eqBits(Math.PI, Double.parseDouble(one(c, "select pi()")));
        eqBits(1024.0, Double.parseDouble(one(c, "select power(2, 10)")));
        eqBits(Math.log(10), Double.parseDouble(one(c, "select ln(10)")));
        return "sqrt, pi, power, ln";
      }
    });

    r.check("extension-functions.c (sqlite-jdbc extras)", () -> {
      try (Connection c = mem()) {
        eq("cba", one(c, "select reverse('abc')"));
        eq("Hello World", one(c, "select proper('hello world')"));
        eq("xxx", one(c, "select replicate('x', 3)"));
        eq("  ab", one(c, "select padl('ab', 4)"));
        return "reverse, proper, replicate, padl";
      }
    });

    r.check("JSON", () -> {
      try (Connection c = mem()) {
        eq("z/OS", one(c,
            "select json_extract('{\"a\":[1,2,{\"b\":\"z/OS\"}]}', '$.a[2].b')"));
        eq("3", one(c, "select json_array_length('[1,2,3]')"));
        eq("{\"k\":\"" + NANDU + "\"}",
            one(c, "select json_object('k', '" + NANDU + "')"));
        return "json_extract, json_array_length, json_object";
      }
    });

    r.check("FTS5 full-text search", () -> {
      try (Connection c = mem()) {
        exec(c, "create virtual table docs using fts5(body)",
            "insert into docs values ('the mainframe runs z/OS')",
            "insert into docs values ('sqlite on the mainframe')",
            "insert into docs values ('nothing to see')");
        eq(2L, longOf(c,
            "select count(*) from docs where docs match 'mainframe'"));
        eq(1L, longOf(c,
            "select count(*) from docs where docs match 'sqlite AND mainframe'"));
        return "match, boolean query";
      }
    });

    r.check("FTS3/4", () -> {
      try (Connection c = mem()) {
        exec(c, "create virtual table f4 using fts4(body)",
            "insert into f4 values ('racf icsf at-tls')");
        eq(1L, longOf(c, "select count(*) from f4 where f4 match 'icsf'"));
        return null;
      }
    });

    r.check("R*Tree spatial index", () -> {
      try (Connection c = mem()) {
        exec(c, "create virtual table rt using rtree(id, x0, x1, y0, y1)",
            "insert into rt values (1, 0, 10, 0, 10)",
            "insert into rt values (2, 20, 30, 20, 30)");
        eq(1L, longOf(c,
            "select id from rt where x0 <= 5 and x1 >= 5 and y0 <= 5 and y1 >= 5"));
        return null;
      }
    });

    r.check("dates and times", () -> {
      try (Connection c = mem()) {
        eq("2026-10-14", one(c, "select date('2026-10-13', '+1 day')"));
        eq("86400", one(c, "select strftime('%s', '1970-01-02 00:00:00')"));
        eqBits(2451544.5,
            Double.parseDouble(one(c, "select julianday('2000-01-01')")));
        eq("2024-02-29", one(c, "select date('2024-03-01', '-1 day')"));
        return "date modifiers, strftime, julianday, leap year";
      }
    });

    r.check("recursive CTE and window functions", () -> {
      try (Connection c = mem()) {
        eq(500500L, longOf(c, "with recursive n(x) as (select 1 union all"
            + " select x + 1 from n where x < 1000) select sum(x) from n"));
        eq("1,3,6,10", one(c, "with v(x) as (values (1),(2),(3),(4))"
            + " select group_concat(s) from (select"
            + " sum(x) over (order by x) s from v)"));
        return null;
      }
    });

    r.check("percentile, dbstat, STAT4", () -> {
      try (Connection c = mem()) {
        eqBits(2.0, Double.parseDouble(one(c,
            "with v(x) as (values (1),(2),(3))"
                + " select percentile(x, 50) from v")));
        exec(c, "create table t(a)", "insert into t values (1)");
        that(longOf(c, "select count(*) from dbstat") > 0, "dbstat empty");
        exec(c, "create index ti on t(a)", "analyze");
        that(longOf(c, "select count(*) from sqlite_stat4") >= 0, "no stat4");
        return null;
      }
    });
  }

  private void jdbcApi() {
    r.section("JDBC: API behaviour");

    r.check("prepared statement column types", () -> {
      try (Connection c = mem()) {
        exec(c, "create table t(i integer, l integer, d real, s text,"
            + " b blob, n text, f integer)");
        try (PreparedStatement p = c.prepareStatement(
            "insert into t values (?, ?, ?, ?, ?, ?, ?)")) {
          p.setInt(1, -42);
          p.setLong(2, 1L << 40);
          p.setDouble(3, 2.5);
          p.setString(4, NANDU);
          p.setBytes(5, new byte[] {1, 2, 3});
          p.setNull(6, Types.VARCHAR);
          p.setBoolean(7, true);
          p.executeUpdate();
        }
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select * from t")) {
          rs.next();
          eq(-42, rs.getInt(1));
          eq(1L << 40, rs.getLong(2));
          eqBits(2.5, rs.getDouble(3));
          eq(NANDU, rs.getString(4));
          that(Arrays.equals(new byte[] {1, 2, 3}, rs.getBytes(5)),
              "blob differs");
          that(rs.getString(6) == null && rs.wasNull(), "null not null");
          eq(true, rs.getBoolean(7));
        }
        return "int, long, double, text, blob, null, boolean";
      }
    });

    r.check("batch insert 5000 rows", () -> {
      try (Connection c = mem()) {
        exec(c, "create table t(i integer, s text)");
        c.setAutoCommit(false);
        try (PreparedStatement p = c.prepareStatement(
            "insert into t values (?, ?)")) {
          for (int i = 0; i < 5000; i++) {
            p.setInt(1, i);
            p.setString(2, "row " + i);
            p.addBatch();
          }
          int[] counts = p.executeBatch();
          eq(5000, counts.length);
        }
        c.commit();
        eq(5000L, longOf(c, "select count(*) from t"));
        eq(12497500L, longOf(c, "select sum(i) from t"));
        return null;
      }
    });

    r.check("generated keys", () -> {
      try (Connection c = mem()) {
        exec(c, "create table t(id integer primary key, v text)",
            "insert into t(v) values ('a')");
        try (PreparedStatement p = c.prepareStatement(
            "insert into t(v) values (?)", Statement.RETURN_GENERATED_KEYS)) {
          p.setString(1, "b");
          p.executeUpdate();
          try (ResultSet k = p.getGeneratedKeys()) {
            that(k.next(), "no generated key");
            eq(2L, k.getLong(1));
          }
        }
        return null;
      }
    });

    r.check("transactions: commit, rollback, savepoint", () -> {
      try (Connection c = mem()) {
        exec(c, "create table t(x)");
        c.setAutoCommit(false);
        exec(c, "insert into t values (1)");
        c.rollback();
        eq(0L, longOf(c, "select count(*) from t"));
        exec(c, "insert into t values (1)");
        Savepoint sp = c.setSavepoint("sp1");
        exec(c, "insert into t values (2)");
        c.rollback(sp);
        c.commit();
        eq(1L, longOf(c, "select count(*) from t"));
        return null;
      }
    });

    r.check("metadata (tables, columns, result set)", () -> {
      try (Connection c = mem()) {
        exec(c, "create table people(id integer primary key,"
            + " name text not null, born integer)");
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet t = md.getTables(null, null, "people", null)) {
          that(t.next(), "table not listed");
        }
        List<String> cols = new ArrayList<>();
        try (ResultSet cr = md.getColumns(null, null, "people", null)) {
          while (cr.next()) {
            cols.add(cr.getString("COLUMN_NAME"));
          }
        }
        eq(Arrays.asList("id", "name", "born"), cols);
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select name as n from people")) {
          ResultSetMetaData rmd = rs.getMetaData();
          eq("n", rmd.getColumnLabel(1));
          eq("people", rmd.getTableName(1));
        }
        return "columns " + cols;
      }
    });

    r.check("foreign keys enforced (SQLiteConfig)", () -> {
      SQLiteConfig cfg = new SQLiteConfig();
      cfg.enforceForeignKeys(true);
      try (Connection c = cfg.createConnection("jdbc:sqlite::memory:")) {
        exec(c, "create table p(id integer primary key)",
            "create table ch(pid integer references p(id))",
            "insert into p values (1)",
            "insert into ch values (1)");
        Throwable t = expectThrows("FK violation",
            () -> exec(c, "insert into ch values (99)"));
        that(String.valueOf(t.getMessage()).contains("FOREIGN KEY"),
            "message was: " + t.getMessage());
        return null;
      }
    });

    r.check("Statement.cancel() interrupts a runaway query", 60, () -> {
      try (Connection c = mem(); Statement s = c.createStatement()) {
        Thread killer = new Thread(() -> {
          try {
            Thread.sleep(1000);
            s.cancel();
          } catch (Exception ignored) {
            // the assertion below reports it
          }
        });
        long t0 = System.currentTimeMillis();
        killer.start();
        Throwable t = expectThrows("interrupt", () -> {
          try (ResultSet rs = s.executeQuery("with recursive n(x) as"
              + " (select 1 union all select x + 1 from n)"
              + " select count(*) from n")) {
            rs.next();
          }
        });
        killer.join();
        long ms = System.currentTimeMillis() - t0;
        that(String.valueOf(t.getMessage()).contains("interrupt"),
            "unexpected: " + t);
        return "stopped after " + ms + " ms";
      }
    });
  }

  private void callbacks() {
    r.section("JDBC: Java callbacks from native code (JNI)");

    r.check("scalar function (Function)", () -> {
      try (Connection c = mem()) {
        Function.create(c, "zos_twice", new Function() {
          @Override
          protected void xFunc() throws SQLException {
            result(value_long(0) * 2);
          }
        });
        Function.create(c, "zos_echo", new Function() {
          @Override
          protected void xFunc() throws SQLException {
            result(value_text(0) + "!");
          }
        });
        Function.create(c, "zos_blob_len", new Function() {
          @Override
          protected void xFunc() throws SQLException {
            result(value_blob(0).length);
          }
        });
        eq(42L, longOf(c, "select zos_twice(21)"));
        eq(NANDU + "!", one(c, "select zos_echo('" + NANDU + "')"));
        eq(3L, longOf(c, "select zos_blob_len(x'000102')"));
        return "long, text, blob arguments";
      }
    });

    r.check("aggregate function (Function.Aggregate)", () -> {
      try (Connection c = mem()) {
        Function.create(c, "zos_sumsq", new Function.Aggregate() {
          private long acc;

          @Override
          protected void xStep() throws SQLException {
            long v = value_long(0);
            acc += v * v;
          }

          @Override
          protected void xFinal() throws SQLException {
            result(acc);
          }
        });
        eq(30L, longOf(c, "with v(x) as (values (1),(2),(3),(4))"
            + " select zos_sumsq(x) from v"));
        return "1+4+9+16 = 30";
      }
    });

    r.check("window function (Function.Window)", () -> {
      try (Connection c = mem()) {
        Function.create(c, "zos_wsum", new Function.Window() {
          private long acc;

          @Override
          protected void xStep() throws SQLException {
            acc += value_long(0);
          }

          @Override
          protected void xInverse() throws SQLException {
            acc -= value_long(0);
          }

          @Override
          protected void xValue() throws SQLException {
            result(acc);
          }

          @Override
          protected void xFinal() throws SQLException {
            result(acc);
          }
        });
        eq("1,3,5,7", one(c, "with v(x) as (values (1),(2),(3),(4))"
            + " select group_concat(s) from (select zos_wsum(x)"
            + " over (order by x rows between 1 preceding and current row) s"
            + " from v)"));
        return "sliding window 1,3,5,7";
      }
    });

    r.check("custom collation (Collation)", () -> {
      try (Connection c = mem()) {
        Collation.create(c, "ZOS_REVERSE", new Collation() {
          @Override
          protected int xCompare(String a, String b) {
            return b.compareTo(a);
          }
        });
        eq("c,b,a", one(c, "with v(x) as (values ('a'),('c'),('b'))"
            + " select group_concat(x) from (select x from v"
            + " order by x collate ZOS_REVERSE)"));
        return "order c,b,a";
      }
    });

    r.check("update and commit listeners", () -> {
      try (Connection c = mem()) {
        List<String> events = new ArrayList<>();
        AtomicInteger commits = new AtomicInteger();
        SQLiteConnection sc = c.unwrap(SQLiteConnection.class);
        SQLiteUpdateListener ul = (type, db, table, rowId) ->
            events.add(type + ":" + table + ":" + rowId);
        SQLiteCommitListener cl = new SQLiteCommitListener() {
          @Override
          public void onCommit() {
            commits.incrementAndGet();
          }

          @Override
          public void onRollback() {
          }
        };
        sc.addUpdateListener(ul);
        sc.addCommitListener(cl);
        exec(c, "create table t(x)",
            "insert into t values (1)",
            "update t set x = 2",
            "delete from t where x = 2");
        sc.removeUpdateListener(ul);
        sc.removeCommitListener(cl);
        eq(Arrays.asList("INSERT:t:1", "UPDATE:t:1", "DELETE:t:1"), events);
        that(commits.get() >= 3, "commits seen: " + commits.get());
        return events + ", commits " + commits.get();
      }
    });

    r.check("progress handler (ProgressHandler)", () -> {
      try (Connection c = mem()) {
        AtomicInteger calls = new AtomicInteger();
        ProgressHandler.setHandler(c, 1000, new ProgressHandler() {
          @Override
          protected int progress() {
            calls.incrementAndGet();
            return 0;
          }
        });
        longOf(c, "with recursive n(x) as (select 1 union all"
            + " select x + 1 from n where x < 100000) select sum(x) from n");
        ProgressHandler.clearHandler(c);
        that(calls.get() > 0, "handler never called");
        return calls.get() + " callbacks";
      }
    });

    r.check("busy handler (BusyHandler) on a locked file", 60, () -> {
      String p = path("zos-busy.db");
      try (Connection a = open(p); Connection b = open(p)) {
        exec(a, "create table t(x)");
        AtomicInteger calls = new AtomicInteger();
        BusyHandler.setHandler(b, new BusyHandler() {
          @Override
          protected int callback(int n) {
            calls.incrementAndGet();
            return n < 3 ? 1 : 0;
          }
        });
        a.setAutoCommit(false);
        exec(a, "insert into t values (1)");
        exec(a, "update t set x = 2"); // a holds the write lock
        Throwable t = expectThrows("SQLITE_BUSY",
            () -> exec(b, "insert into t values (9)"));
        a.commit();
        that(calls.get() > 0, "busy handler not called; got " + t);
        that(String.valueOf(t.getMessage()).contains("BUSY")
                || String.valueOf(t.getMessage()).contains("locked"),
            "unexpected error: " + t.getMessage());
        return calls.get() + " callbacks, then " + t.getMessage();
      }
    });
  }

  private void files() {
    r.section("JDBC: files and locking in " + dir);

    r.check("create, close, reopen a database file", () -> {
      String p = path("zos-basic.db");
      try (Connection c = open(p)) {
        exec(c, "create table t(id integer primary key, s text)",
            "insert into t(s) values ('one'), ('two'), ('" + NANDU + "')");
      }
      File f = new File(p);
      that(f.isFile() && f.length() > 0, "file not written: " + p);
      try (Connection c = open(p)) {
        eq(3L, longOf(c, "select count(*) from t"));
        eq(NANDU, one(c, "select s from t where id = 3"));
        eq("ok", one(c, "pragma integrity_check"));
      }
      return p + " (" + f.length() + " bytes)";
    });

    r.check("URL pragmas are applied", () -> {
      String p = path("zos-url.db");
      String url = "jdbc:sqlite:" + p
          + "?busy_timeout=4321&foreign_keys=on&cache_size=-4000";
      try (Connection c = DriverManager.getConnection(url)) {
        eq("4321", one(c, "pragma busy_timeout"));
        eq("1", one(c, "pragma foreign_keys"));
        eq("-4000", one(c, "pragma cache_size"));
        return null;
      }
    });

    r.check("rollback journal: reader sees committed data only", 60, () -> {
      String p = path("zos-journal.db");
      SQLiteConfig cfg = new SQLiteConfig();
      cfg.setBusyTimeout(10000);
      try (Connection w = open(p, cfg); Connection rd = open(p, cfg)) {
        eq("delete", one(w, "pragma journal_mode"));
        exec(w, "create table t(x)", "insert into t values (1)");
        w.setAutoCommit(false);
        exec(w, "insert into t values (2)");
        eq(1L, longOf(rd, "select count(*) from t"));
        w.commit();
        eq(2L, longOf(rd, "select count(*) from t"));
        return "fcntl locks between two connections";
      }
    });

    r.check("WAL mode: concurrent reader and writer", 60, () -> {
      String p = path("zos-wal.db");
      SQLiteConfig cfg = new SQLiteConfig();
      cfg.setBusyTimeout(10000);
      try (Connection w = open(p, cfg)) {
        eq("wal", one(w, "pragma journal_mode=WAL"));
        exec(w, "create table t(x)", "insert into t values (1)");
        try (Connection rd = open(p, cfg)) {
          rd.setAutoCommit(false);
          eq(1L, longOf(rd, "select count(*) from t")); // snapshot open
          exec(w, "insert into t values (2)");
          eq(1L, longOf(rd, "select count(*) from t")); // still snapshot
          rd.commit();
          eq(2L, longOf(rd, "select count(*) from t"));
        }
        that(new File(p + "-shm").exists() || new File(p + "-wal").exists(),
            "no -wal/-shm file");
        exec(w, "pragma wal_checkpoint(TRUNCATE)");
        return "snapshot isolation; -shm uses mmap";
      }
    });

    r.check("4 threads x 100 writes, separate connections", 180, () -> {
      String p = path("zos-threads.db");
      try (Connection c = open(p)) {
        exec(c, "create table t(thread integer, n integer)");
      }
      int threads = 4;
      int per = 100;
      CountDownLatch start = new CountDownLatch(1);
      AtomicReference<Throwable> err = new AtomicReference<>();
      List<Thread> ts = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        final int id = i;
        Thread t = new Thread(() -> {
          SQLiteConfig cfg = new SQLiteConfig();
          cfg.setBusyTimeout(60000);
          try (Connection c = open(p, cfg);
               PreparedStatement ps = c.prepareStatement(
                   "insert into t values (?, ?)")) {
            start.await();
            for (int n = 0; n < per; n++) {
              ps.setInt(1, id);
              ps.setInt(2, n);
              ps.executeUpdate();
            }
          } catch (Throwable e) {
            err.compareAndSet(null, e);
          }
        });
        t.start();
        ts.add(t);
      }
      start.countDown();
      for (Thread t : ts) {
        t.join();
      }
      if (err.get() != null) {
        throw new AssertionError("writer failed: "
            + Report.describe(err.get()));
      }
      try (Connection c = open(p)) {
        eq((long) threads * per, longOf(c, "select count(*) from t"));
      }
      return threads * per + " rows";
    });

    r.check("50,000 rows, integrity check, VACUUM", 300, () -> {
      String p = path("zos-bulk.db");
      try (Connection c = open(p)) {
        exec(c, "create table t(id integer primary key, s text, d real)");
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
            "insert into t(s, d) values (?, ?)")) {
          for (int i = 0; i < 50_000; i++) {
            ps.setString(1, "row-" + i + "-" + NANDU);
            ps.setDouble(2, i / 7.0);
            ps.addBatch();
            if (i % 5000 == 4999) {
              ps.executeBatch();
            }
          }
          ps.executeBatch();
        }
        c.commit();
        c.setAutoCommit(true);
        exec(c, "create index t_s on t(s)", "delete from t where id % 2 = 0");
        eq("ok", one(c, "pragma integrity_check"));
        exec(c, "vacuum");
        eq(25_000L, longOf(c, "select count(*) from t"));
      }
      return new File(p).length() / 1024 + " KB after vacuum";
    });

    r.check("online backup and restore", 120, () -> {
      String src = path("zos-backup-src.db");
      String dst = path("zos-backup-copy.db");
      try (Connection c = open(src)) {
        exec(c, "create table t(x)", "insert into t values (1), (2), (3)");
        exec(c, "backup to " + dst);
      }
      try (Connection c = mem()) {
        exec(c, "restore from " + dst);
        eq(3L, longOf(c, "select count(*) from t"));
      }
      return new File(dst).length() + " byte copy";
    });

    r.check("read-only connection rejects writes", () -> {
      String p = path("zos-ro.db");
      try (Connection c = open(p)) {
        exec(c, "create table t(x)");
      }
      SQLiteConfig cfg = new SQLiteConfig();
      cfg.setReadOnly(true);
      try (Connection c = open(p, cfg)) {
        Throwable t = expectThrows("readonly",
            () -> exec(c, "insert into t values (1)"));
        that(String.valueOf(t.getMessage()).toLowerCase().contains("readonly")
                || String.valueOf(t.getMessage()).contains("READONLY"),
            "message was: " + t.getMessage());
      }
      return null;
    });
  }
}
