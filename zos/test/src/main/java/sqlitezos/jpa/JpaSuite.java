package sqlitezos.jpa;

import static sqlitezos.Report.causedBy;
import static sqlitezos.Report.eq;
import static sqlitezos.Report.eqBits;
import static sqlitezos.Report.expectThrows;
import static sqlitezos.Report.that;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import sqlitezos.Main;
import sqlitezos.Report;

/**
 * JPA checks through Hibernate 6 + the community SQLiteDialect, against a
 * database file in the test directory. Checks run in order and share data.
 */
public final class JpaSuite implements Main.Suite {

  /** Keep a strong reference so the level setting is not garbage collected. */
  private static final Logger HIBERNATE_LOG = Logger.getLogger("org.hibernate");

  static final String NANDU_NAME = "Ñandú Pérez";
  static final String GAMMA = "Gamma ñ";

  private final String dir;
  private final boolean keep;
  private final Report r;
  private EntityManagerFactory emf;
  private Long nanduId;
  private Long graceId;

  public JpaSuite(String dir, boolean keep, Report r) {
    this.dir = dir;
    this.keep = keep;
    this.r = r;
  }

  private <T> T tx(Function<EntityManager, T> work) {
    EntityManager em = emf.createEntityManager();
    try {
      em.getTransaction().begin();
      T result = work.apply(em);
      em.getTransaction().commit();
      return result;
    } catch (RuntimeException e) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      throw e;
    } finally {
      em.close();
    }
  }

  private long bookCount() {
    return tx(em -> em.createQuery("select count(b) from Book b", Long.class)
        .getSingleResult());
  }

  private static void deleteDb(String p) {
    for (String s : new String[] {"", "-journal", "-wal", "-shm"}) {
      new File(p + s).delete();
    }
  }

  @Override
  public void run() {
    HIBERNATE_LOG.setLevel(Level.WARNING);
    r.section("JPA: Hibernate + SQLiteDialect");
    String db = new File(dir, "zos-jpa.db").getPath();
    deleteDb(db);

    r.check("EntityManagerFactory starts, schema created", 300, () -> {
      Map<String, Object> props = new HashMap<>();
      props.put("jakarta.persistence.jdbc.url",
          "jdbc:sqlite:" + db + "?busy_timeout=10000");
      emf = Persistence.createEntityManagerFactory("sqlitezos", props);
      long tables = tx(em -> ((Number) em.createNativeQuery(
          "select count(*) from sqlite_master where type = 'table'"
              + " and name in ('author', 'book')")
          .getSingleResult()).longValue());
      eq(2L, tables);
      return db;
    });

    if (emf == null) {
      r.skip("remaining JPA checks", "EntityManagerFactory did not start");
      return;
    }

    try {
      checks();
    } finally {
      emf.close();
      if (!keep) {
        deleteDb(db);
      }
    }
  }

  private void checks() {
    r.check("persist an object graph (IDENTITY ids, cascade)", () -> {
      Author[] saved = tx(em -> {
        Author a = new Author(NANDU_NAME, "nandu@example.com");
        a.addBook("Alpha", 10.5, 100, true);
        a.addBook("Beta", 20.25, 250, false);
        a.addBook(GAMMA, 30.0, 300, true);
        Author g = new Author("Grace Hopper", "grace@example.com");
        g.addBook("COBOL", 40.0, 400, true);
        em.persist(a);
        em.persist(g);
        return new Author[] {a, g};
      });
      nanduId = saved[0].getId();
      graceId = saved[1].getId();
      that(nanduId != null && graceId != null, "ids not generated");
      eq(4L, bookCount());
      return "author ids " + nanduId + ", " + graceId;
    });

    r.check("find by id, text round trip, lazy collection", () -> {
      List<String> titles = tx(em -> {
        Author a = em.find(Author.class, nanduId);
        eq(NANDU_NAME, a.getName());
        eq(0, a.getVersion());
        return a.getBooks().stream().map(Book::getTitle).toList();
      });
      eq(Arrays.asList("Alpha", "Beta", GAMMA), titles);
      return titles.toString();
    });

    r.check("JPQL join with named parameter", () -> {
      List<String> titles = tx(em -> em.createQuery(
              "select b.title from Book b join b.author a"
                  + " where a.email = :e order by b.title", String.class)
          .setParameter("e", "nandu@example.com")
          .getResultList());
      eq(Arrays.asList("Alpha", "Beta", GAMMA), titles);
      return null;
    });

    r.check("JPQL aggregates (count, avg, max, sum)", () -> {
      Object[] row = tx(em -> em.createQuery(
              "select count(b), avg(b.price), max(b.pages), sum(b.pages)"
                  + " from Book b", Object[].class)
          .getSingleResult());
      eq(4L, ((Number) row[0]).longValue());
      eqBits(25.1875, ((Number) row[1]).doubleValue());
      eq(400, ((Number) row[2]).intValue());
      eq(1050L, ((Number) row[3]).longValue());
      return "count 4, avg 25.1875, max 400, sum 1050";
    });

    r.check("Criteria API (double and boolean predicates)", () -> {
      List<String> titles = tx(em -> {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Book> q = cb.createQuery(Book.class);
        Root<Book> b = q.from(Book.class);
        q.select(b)
            .where(cb.gt(b.get("price"), 15.0), cb.isTrue(b.get("inPrint")))
            .orderBy(cb.asc(b.get("title")));
        return em.createQuery(q).getResultList().stream()
            .map(Book::getTitle).toList();
      });
      eq(Arrays.asList("COBOL", GAMMA), titles);
      return titles.toString();
    });

    r.check("pagination (LIMIT/OFFSET)", () -> {
      List<String> titles = tx(em -> em.createQuery(
              "select b.title from Book b order by b.title", String.class)
          .setFirstResult(1)
          .setMaxResults(2)
          .getResultList());
      eq(Arrays.asList("Beta", "COBOL"), titles);
      return null;
    });

    r.check("native SQL query", () -> {
      Object v = tx(em -> em.createNativeQuery("select sqlite_version()")
          .getSingleResult());
      that(String.valueOf(v).startsWith("3."), "got " + v);
      return "sqlite " + v;
    });

    r.check("transaction rollback discards changes", () -> {
      long before = bookCount();
      EntityManager em = emf.createEntityManager();
      try {
        em.getTransaction().begin();
        Author a = em.find(Author.class, graceId);
        a.addBook("Never saved", 1.0, 1, false);
        em.flush();
        em.getTransaction().rollback();
      } finally {
        em.close();
      }
      eq(before, bookCount());
      return null;
    });

    r.check("optimistic locking (@Version)", () -> {
      EntityManager em1 = emf.createEntityManager();
      try {
        em1.getTransaction().begin();
        Author stale = em1.find(Author.class, nanduId);
        em1.getTransaction().commit();

        tx(em2 -> {
          em2.find(Author.class, nanduId).setName(NANDU_NAME + " II");
          return null;
        });

        em1.getTransaction().begin();
        stale.setName("lost update");
        Throwable t = expectThrows("optimistic lock failure",
            () -> em1.getTransaction().commit());
        if (em1.getTransaction().isActive()) {
          em1.getTransaction().rollback();
        }
        that(causedBy(t, "OptimisticLock", "StaleObjectState", "StaleState"),
            "unexpected exception: " + Report.describe(t));
      } finally {
        em1.close();
      }
      int version = tx(em -> em.find(Author.class, nanduId).getVersion());
      eq(1, version);
      return "second writer rejected, version now 1";
    });

    r.check("unique constraint violation is reported", () -> {
      Throwable t = expectThrows("duplicate email", () -> tx(em -> {
        em.persist(new Author("Copy", "grace@example.com"));
        return null;
      }));
      boolean unique = false;
      for (Throwable c = t; c != null; c = c.getCause()) {
        if (String.valueOf(c.getMessage()).contains("UNIQUE")) {
          unique = true;
        }
        if (c.getCause() == c) {
          break;
        }
      }
      that(unique, "no UNIQUE in error chain: " + t);
      return null;
    });

    r.check("JPQL bulk update and delete", () -> {
      int updated = tx(em -> em.createQuery(
              "update Book b set b.price = b.price * 2 where b.pages >= :p")
          .setParameter("p", 300)
          .executeUpdate());
      eq(2, updated);
      double cobol = tx(em -> em.createQuery(
              "select b.price from Book b where b.title = 'COBOL'", Double.class)
          .getSingleResult());
      eqBits(80.0, cobol);
      int deleted = tx(em -> em.createQuery(
              "delete from Book b where b.inPrint = false")
          .executeUpdate());
      eq(1, deleted);
      eq(3L, bookCount());
      return "2 updated, 1 deleted";
    });

    r.check("orphan removal and cascade delete", () -> {
      tx(em -> {
        Author a = em.find(Author.class, nanduId);
        a.getBooks().removeIf(b -> b.getTitle().equals("Alpha"));
        return null;
      });
      eq(2L, bookCount());
      tx(em -> {
        em.remove(em.find(Author.class, graceId));
        return null;
      });
      eq(1L, bookCount());
      long authors = tx(em -> em.createQuery("select count(a) from Author a",
          Long.class).getSingleResult());
      eq(1L, authors);
      return null;
    });
  }
}
