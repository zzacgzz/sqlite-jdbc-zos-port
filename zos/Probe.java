import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class Probe {
  static void show(Statement s, String sql)
      throws Exception {
    try (ResultSet r = s.executeQuery(sql)) {
      r.next();
      System.out.println(sql + "  ->  "
          + r.getString(1));
    }
  }

  public static void main(String[] a)
      throws Exception {
    String db = a.length > 0 ? a[0]
        : "/tmp/probe.db";
    String url = "jdbc:sqlite:" + db;
    try (Connection c =
             DriverManager.getConnection(url);
         Statement s = c.createStatement()) {
      show(s, "select sqlite_version()");
      // IEEE float check: expect 3.375
      show(s, "select 1.5 * 2.25");
      // charset round trip, display-independent
      String w = "ñandú";
      // expect C3B1616E64C3BA (UTF-8 bytes)
      show(s, "select hex('" + w + "')");
      // expect 5 characters
      show(s, "select length('" + w + "')");
      try (ResultSet r = s.executeQuery(
               "select '" + w + "'")) {
        r.next();
        // expect true
        System.out.println("roundtrip equal  ->  "
            + w.equals(r.getString(1)));
      }
      // expect 1 (sqlite-jdbc patch present)
      show(s, "select sqlite_compileoption_used"
          + "('JDBC_EXTENSIONS')");
      // math functions: expect 4.0
      show(s, "select sqrt(16)");
      // extension-functions.c: expect 3
      show(s, "select ceil(2.1)");

      // file I/O + locking on zFS
      s.executeUpdate("create table if not "
          + "exists t(x real, y text)");
      s.executeUpdate("insert into t values "
          + "(3.14159, 'z/OS')");
      show(s, "select count(*) from t");

      // prepared statement + text binding
      try (java.sql.PreparedStatement p =
               c.prepareStatement(
                   "select ? || ' ok'")) {
        p.setString(1, "bind");
        try (ResultSet r = p.executeQuery()) {
          r.next();
          System.out.println("bind  ->  "
              + r.getString(1));
        }
      }
      System.out.println("ALL DONE");
    }
  }
}
