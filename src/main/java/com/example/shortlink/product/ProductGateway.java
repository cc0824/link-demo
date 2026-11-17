package com.example.shortlink.product;

import java.util.Optional;

public interface ProductGateway {
  Optional<Product> findById(long productId);
}
