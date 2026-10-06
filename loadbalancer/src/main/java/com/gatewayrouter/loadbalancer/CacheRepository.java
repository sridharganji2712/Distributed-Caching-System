package com.gatewayrouter.loadbalancer;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CacheRepository extends MongoRepository<CacheEntry, String> {
    // Spring Data MongoDB automatically generates all standard CRUD methods (save, findById, delete)
}