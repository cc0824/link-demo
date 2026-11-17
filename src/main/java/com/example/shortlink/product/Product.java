package com.example.shortlink.product;

public record Product(long id, String title, String description, String imageRef, String status) {
  public boolean active() {
    return "ACTIVE".equals(status);
  }
}
