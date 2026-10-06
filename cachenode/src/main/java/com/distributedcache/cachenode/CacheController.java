package com.distributedcache.cachenode;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cache")
public class CacheController {

    private final CacheEvictionService cacheEvictionService;

    public CacheController(CacheEvictionService cacheEvictionService) {
        this.cacheEvictionService = cacheEvictionService;
    }

    /**
     * Endpoint to store or update data with an optional Time-To-Live (TTL) parameter.
     * Maps to: PUT /api/v1/cache/{key}?ttl=60
     */
    @PutMapping("/{key}")
    public ResponseEntity<String> putData(
            @PathVariable String key,
            @RequestBody String value,
            @RequestParam(defaultValue = "0") long ttl) {

        // 1. High-concurrency telemetry logging to monitor entry windows
        System.out.println("--- PUT REQUEST RECEIVED --- Thread: [" + Thread.currentThread().getName()
                + "] | KEY: [" + key + "] | TTL: " + ttl + "s");

        // 2. Delegate the write operation to our thread-safe, atomic service tier
        cacheEvictionService.put(key, value, ttl);

        // 3. Return an immediate HTTP 200 OK success indicator
        return ResponseEntity.ok("Data saved successfully.");
    }

    /**
     * Endpoint to fetch data from the cache node with non-blocking reads.
     * Maps to: GET /api/v1/cache/{key}
     */
    @GetMapping("/{key}")
    public ResponseEntity<String> getData(@PathVariable String key) {
        System.out.println("--- GET REQUEST RECEIVED --- KEY SEARCHED: [" + key + "]");

        String value = cacheEvictionService.get(key);

        if (value == null) {
            System.out.println("-> CACHE MISS for key: [" + key + "]");
            return ResponseEntity.notFound().build(); // Returns HTTP 404
        }

        System.out.println("-> CACHE HIT! Found value for key: [" + key + "]");
        return ResponseEntity.ok(value); // Returns HTTP 200 OK with the value
    }


    @DeleteMapping("/{key}")
    public ResponseEntity<String> deleteData(@PathVariable String key) {
        System.out.println("--- DELETE REQUEST RECEIVED --- KEY: [" + key + "]");
        cacheEvictionService.delete(key);
        return ResponseEntity.ok("Key [" + key + "] deleted successfully.");
    }
    /**
     * Telemetry health endpoint used by the Gateway-Router to monitor cluster status.
     * Maps to: GET /api/v1/cache/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> getHealth() {
        // Dynamically fetch the current size from the concurrent map
        int currentSize = cacheEvictionService.getCurrentSize();

        // Returns an updated JSON payload showing node health and current RAM footprint
        return ResponseEntity.ok("{\"status\": \"UP\", \"keysStored\": " + currentSize + "}");
    }
}