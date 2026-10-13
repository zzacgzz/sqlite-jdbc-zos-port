package sqlitezos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tiny test harness: runs each check on a worker thread with a timeout, so
 * a hang in native code (locks, mmap, ...) is reported as a failure instead
 * of freezing the whole run.
 */
public final class Report {

  /** A check body. Return an optional detail string, throw to fail. */
  public interface Body {
    String run() throws Exception;
  }

  private static final int DEFAULT_TIMEOUT_SEC = 120;

  private final boolean verbose;
  private final List<String> failures = new ArrayList<>();
  private int pass;
  private int fail;
  private int skip;
  private ExecutorService exec = newExec();

  public Report(boolean verbose) {
    this.verbose = verbose;
  }

  private static ExecutorService newExec() {
    return Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "check");
      t.setDaemon(true);
      return t;
    });
  }

  public void section(String title) {
    System.out.println();
    System.out.println("== " + title);
  }

  public void check(String name, Body body) {
    check(name, DEFAULT_TIMEOUT_SEC, body);
  }

  public void check(String name, int timeoutSec, Body body) {
    long t0 = System.nanoTime();
    Future<String> f = exec.submit(body::run);
    try {
      String detail = f.get(timeoutSec, TimeUnit.SECONDS);
      pass++;
      line("PASS", name, detail, t0);
    } catch (TimeoutException e) {
      f.cancel(true);
      exec.shutdownNow();
      exec = newExec();
      failed(name, "timed out after " + timeoutSec + "s (possible hang)", t0);
    } catch (ExecutionException e) {
      Throwable c = e.getCause();
      failed(name, describe(c), t0);
      if (verbose) {
        c.printStackTrace(System.out);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failed(name, "interrupted", t0);
    }
  }

  public void skip(String name, String why) {
    skip++;
    System.out.println("[SKIP] " + name + " - " + why);
  }

  private void failed(String name, String why, long t0) {
    fail++;
    failures.add(name + ": " + why);
    line("FAIL", name, why, t0);
  }

  private static void line(String tag, String name, String detail, long t0) {
    long ms = (System.nanoTime() - t0) / 1_000_000;
    StringBuilder sb = new StringBuilder();
    sb.append('[').append(tag).append("] ").append(name);
    sb.append("  (").append(ms).append(" ms)");
    if (detail != null && !detail.isEmpty()) {
      sb.append("  ").append(detail);
    }
    System.out.println(sb);
  }

  public static String describe(Throwable t) {
    StringBuilder sb = new StringBuilder();
    sb.append(t.getClass().getSimpleName());
    if (t.getMessage() != null) {
      sb.append(": ").append(t.getMessage());
    }
    Throwable root = t;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    if (root != t) {
      sb.append("  <- root cause ").append(root.getClass().getSimpleName());
      if (root.getMessage() != null) {
        sb.append(": ").append(root.getMessage());
      }
    }
    return sb.toString().replace('\n', ' ');
  }

  /** Prints the summary and returns the number of failures. */
  public int summary() {
    exec.shutdownNow();
    System.out.println();
    System.out.println("==================================================");
    System.out.println("PASSED " + pass + "   FAILED " + fail
        + "   SKIPPED " + skip);
    for (String f : failures) {
      System.out.println("  FAILED: " + f);
    }
    System.out.println(fail == 0 ? "RESULT: ALL CHECKS PASSED"
        : "RESULT: SOME CHECKS FAILED");
    return fail;
  }

  // ---------------------------------------------------------------- asserts

  public static void that(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  public static void eq(Object expected, Object actual) {
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError("expected <" + expected + "> but got <"
          + actual + ">");
    }
  }

  /** Bit-exact double comparison (IEEE 754 check). */
  public static void eqBits(double expected, double actual) {
    if (Double.doubleToRawLongBits(expected)
        != Double.doubleToRawLongBits(actual)) {
      throw new AssertionError("expected <" + expected + "> (0x"
          + Long.toHexString(Double.doubleToRawLongBits(expected))
          + ") but got <" + actual + "> (0x"
          + Long.toHexString(Double.doubleToRawLongBits(actual)) + ")");
    }
  }

  /** Runs code that must throw; returns the exception. */
  public static Throwable expectThrows(String what, ThrowingRunnable r) {
    try {
      r.run();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected an exception: " + what);
  }

  public interface ThrowingRunnable {
    void run() throws Exception;
  }

  /** True if t or any cause has a class name containing one of the parts. */
  public static boolean causedBy(Throwable t, String... nameParts) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String n = c.getClass().getName();
      for (String p : nameParts) {
        if (n.contains(p)) {
          return true;
        }
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return false;
  }
}
