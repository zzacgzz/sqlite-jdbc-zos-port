package sqlitezos.jpa;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "author")
public class Author {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String name;

  @Column(unique = true)
  private String email;

  @Version
  private int version;

  @OneToMany(mappedBy = "author", cascade = CascadeType.ALL,
      orphanRemoval = true)
  @OrderBy("title")
  private List<Book> books = new ArrayList<>();

  protected Author() {
  }

  public Author(String name, String email) {
    this.name = name;
    this.email = email;
  }

  public Book addBook(String title, double price, int pages,
      boolean inPrint) {
    Book b = new Book(this, title, price, pages, inPrint);
    books.add(b);
    return b;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getEmail() {
    return email;
  }

  public int getVersion() {
    return version;
  }

  public List<Book> getBooks() {
    return books;
  }
}
