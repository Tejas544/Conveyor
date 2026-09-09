package com.conveyor.inventory.catalog;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CatalogRepository extends MongoRepository<CatalogDocument, String> {}
