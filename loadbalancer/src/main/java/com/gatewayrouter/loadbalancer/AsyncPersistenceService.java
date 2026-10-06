package com.gatewayrouter.loadbalancer;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AsyncPersistenceService {
    private final CacheRepository cacheRepository;

    public AsyncPersistenceService(CacheRepository cacheRepository) {
        this.cacheRepository = cacheRepository;
    }

   @Async
public void saveToDatabase(String key, String value, long expiryTime) {
    long writeTimestamp = System.currentTimeMillis();

    cacheRepository.findById(key).ifPresentOrElse(
        existing -> {
            // only overwrite if this write is newer than what MongoDB already has
            if (writeTimestamp >= existing.getLastSavedTimestamp()) {
                existing.setValue(value);
                existing.setExpiryTime(expiryTime);
                existing.setLastSavedTimestamp(writeTimestamp);
                cacheRepository.save(existing);
                System.out.println("Async update saved for key: " + key);
            } else {
                System.out.println("Async write SKIPPED for key: " + key + " (stale write discarded)");
            }
        },
        () -> {
            // key doesn't exist yet, safe to insert
            CacheEntry entry = new CacheEntry(key, value, expiryTime);
            entry.setLastSavedTimestamp(writeTimestamp);
            cacheRepository.save(entry);
            System.out.println("Async insert saved for key: " + key);
        }
    );
}
@Async
public void replicateToNode(String nodeUrl, String key, String value, long ttl) {
    try {
        org.springframework.web.client.RestTemplate replicaTemplate =
            new org.springframework.web.client.RestTemplate();
        replicaTemplate.put(
            nodeUrl + "/api/v1/cache/" + key + "?ttl=" + ttl,
            value
        );
        System.out.println("ASYNC REPLICA: Successfully replicated key [" + key + "] to " + nodeUrl);
    } catch (Exception e) {
        System.out.println("ASYNC REPLICA FAILED: Could not replicate key [" + key + "] to " + nodeUrl + ": " + e.getMessage());
    }
}
}
