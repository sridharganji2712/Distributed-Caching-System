package com.distributedcache.cachenode;

public interface CacheEvictionService {

    String get(String key);

    void put(String key, String value, long ttlInSeconds);

    void delete(String key);

    int getCurrentSize();
}