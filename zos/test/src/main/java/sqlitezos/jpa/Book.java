package sqlitezos.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "book")
public class Book {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String title;

  private double price;

  private int pages;

  private boolean inPrint;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "author_id")
  private Author author;

  protected Book() {
  }

  Book(Author author, String title, double price, int pages,
      boolean inPrint) {
    this.author = author;
    this.title = title;
    this.price = price;
    this.pages = pages;
    this.inPrint = inPrint;
  }

  public Long getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public double getPrice() {
    return price;
  }

  public void setPrice(double price) {
    this.price = price;
  }

  public int getPages() {
    return pages;
  }

  public boolean isInPrint() {
    return inPrint;
  }

  public Author getAuthor() {
    return author;
  }
}
