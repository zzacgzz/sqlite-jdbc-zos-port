package sqlitezos;

import java.io.File;
import java.nio.charset.Charset;

/**
 * Entry point.
 *
 * <pre>
 *   java -cp sqlite-zos-test.jar:sqlite-jdbc-X-zos.jar sqlitezos.Main [options]
 *
 *   --dir PATH     directory for test database files (default /tmp)
 *   --jdbc-only    skip the JPA (Hibernate) suite
 *   --jpa-only     skip the JDBC suite
 *   --keep         keep the test database files afterwards
 *   --verbose      print stack traces for failures
 * </pre>
 *
 * Exit code 0 = all checks passed, 1 = at least one failure, 2 = bad usage.
 */
public final class Main {

  /** Implemented by both suites so Main can load the JPA one by name. */
  public interface Suite {
    void run();
  }

  private Main() {
  }

  public static void main(String[] args) {
    String dir = "/tmp";
    boolean jdbc = true;
    boolean jpa = true;
    boolean keep = false;
    boolean verbose = false;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--dir":
          if (i + 1 >= args.length) {
            usage("--dir needs a path");
          }
          dir = args[++i];
          break;
        case "--jdbc-only":
          jpa = false;
          break;
        case "--jpa-only":
          jdbc = false;
          break;
        case "--keep":
          keep = true;
          break;
        case "--verbose":
          verbose = true;
          break;
        case "-h":
        case "--help":
          usage(null);
          break;
        default:
          usage("unknown option " + args[i]);
      }
    }

    File d = new File(dir);
    if (!d.isDirectory() || !d.canWrite()) {
      System.out.println("ERROR: --dir " + dir
          + " is not a writable directory");
      System.exit(2);
    }

    Report report = new Report(verbose);
    header(dir);

    if (jdbc) {
      new JdbcSuite(dir, keep, report).run();
    }
    if (jpa) {
      runJpa(dir, keep, report);
    }

    int failed = report.summary();
    System.exit(failed == 0 ? 0 : 1);
  }

  private static void header(String dir) {
    System.out.println("sqlite-jdbc z/OS test suite");
    System.out.println("  java      " + System.getProperty("java.version")
        + " (" + System.getProperty("java.vendor") + ")");
    System.out.println("  os        " + System.getProperty("os.name")
        + " / " + System.getProperty("os.arch"));
    System.out.println("  charset   default=" + Charset.defaultCharset()
        + " file.encoding=" + System.getProperty("file.encoding"));
    String lib = System.getProperty("org.sqlite.lib.path");
    System.out.println("  native    " + (lib == null
        ? "from the jar (org/sqlite/native/...)"
        : "org.sqlite.lib.path=" + lib));
    String canon;
    try {
      canon = new File(dir).getCanonicalPath();
    } catch (Exception e) {
      canon = "?";
    }
    System.out.println("  test dir  " + dir
        + (canon.equals(dir) ? "" : "  (resolves to " + canon + ")"));
  }

  private static void runJpa(String dir, boolean keep, Report report) {
    Class<?> suite;
    try {
      Class.forName("jakarta.persistence.Persistence");
      suite = Class.forName("sqlitezos.jpa.JpaSuite");
    } catch (ClassNotFoundException e) {
      report.section("JPA (Hibernate)");
      report.skip("JPA suite",
          "this jar was built without Hibernate - build the full jar"
              + " with 'mvn package' (see README)");
      return;
    }
    try {
      Suite s = (Suite) suite
          .getConstructor(String.class, boolean.class, Report.class)
          .newInstance(dir, keep, report);
      s.run();
    } catch (ReflectiveOperationException e) {
      report.section("JPA (Hibernate)");
      report.check("load JPA suite", () -> {
        throw e;
      });
    }
  }

  private static void usage(String error) {
    if (error != null) {
      System.out.println("ERROR: " + error);
    }
    System.out.println("usage: sqlitezos.Main [--dir PATH] [--jdbc-only]"
        + " [--jpa-only] [--keep] [--verbose]");
    System.exit(error == null ? 0 : 2);
  }
}
