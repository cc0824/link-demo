package com.example.shortlink.product;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
public class ProductSeedInitializer implements ApplicationRunner {
  private final JdbcProductGateway products;

  public ProductSeedInitializer(JdbcProductGateway products) {
    this.products = products;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    products.initializeSeed();
  }
}
