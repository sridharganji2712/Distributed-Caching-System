package com.distributedcache.cachenode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Value("${cache.eviction.strategy:lru}")
    private String strategy;

    @Value("${cache.max.size:1000}")
    private int maxSize;

    @Bean
    public CacheEvictionService cacheEvictionService() {
        if (strategy.equalsIgnoreCase("lfu")) {
            System.out.println("CACHE CONFIG: Starting with LFU | MAX_SIZE: " + maxSize);
            return new LFUCacheEvictionService(maxSize);
        }
        System.out.println("CACHE CONFIG: Starting with LRU | MAX_SIZE: " + maxSize);
        return new LRUCacheEvictionService(maxSize);
    }
}