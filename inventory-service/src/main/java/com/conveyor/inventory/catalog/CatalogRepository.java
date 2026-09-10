package com.conveyor.inventory.catalog;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CatalogRepository extends MongoRepository<CatalogDocument, String> {

  List<CatalogDocument> findByNameContainingIgnoreCase(String name);
}
