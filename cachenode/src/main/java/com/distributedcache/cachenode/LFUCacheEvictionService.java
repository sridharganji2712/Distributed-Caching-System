package com.distributedcache.cachenode;

import org.springframework.scheduling.annotation.Scheduled;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class LFUCacheEvictionService implements CacheEvictionService {

  

    private final int maxSize;

    public LFUCacheEvictionService(int maxSize) {
        this.maxSize = maxSize;
    }

    // key → Node (holds value, frequency, expiry)
    private final Map<String, Node> keyMap = new HashMap<>();

    // frequency → ordered set of keys at that frequency
    // LinkedHashSet preserves insertion order — first key inserted
    // is the oldest, so tiebreaking between same-frequency keys is automatic
    private final Map<Integer, LinkedHashSet<String>> freqMap = new HashMap<>();

    // always points to the lowest frequency currently in the cache
    // so eviction is O(1) — no searching needed
    private int minFreq = 0;

    private final ReentrantLock lock = new ReentrantLock();

    private static class Node {
        String value;
        int frequency;
        long expiryTime;

        Node(String value, int frequency, long expiryTime) {
            this.value = value;
            this.frequency = frequency;
            this.expiryTime = expiryTime;
        }
    }


    private void incrementFrequency(String key, Node node) {
        int oldFreq = node.frequency;

        // remove key from its current frequency bucket
        freqMap.get(oldFreq).remove(key);

        // if this was the only key at minFreq, minFreq moves up
        if (oldFreq == minFreq && freqMap.get(oldFreq).isEmpty()) {
            minFreq++;
        }

        // move key to the next frequency bucket
        node.frequency++;
        freqMap
                .computeIfAbsent(node.frequency, k -> new LinkedHashSet<>())
                .add(key);
    }


    @Override
    public String get(String key) {
        lock.lock();
        try {
            Node node = keyMap.get(key);

            // key doesn't exist
            if (node == null) {
                return null;
            }

            // key exists but TTL has expired — evict it
            if (node.expiryTime != Long.MAX_VALUE && System.currentTimeMillis() > node.expiryTime) {
                // remove from both maps
                keyMap.remove(key);
                freqMap.get(node.frequency).remove(key);
                System.out.println("LFU [LAZY EXPIRY]: Key [" + key + "] has expired. Evicting.");
                return null;
            }

            // key is valid — promote its frequency
            incrementFrequency(key, node);
            return node.value;

        } finally {
            lock.unlock();
        }
    }




    @Override
    public void put(String key, String value, long ttlInSeconds) {
        long expiryTime = (ttlInSeconds <= 0)
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + (ttlInSeconds * 1000);

        lock.lock();
        try {
            if (keyMap.containsKey(key)) {
                // key already exists — update value and expiry, then promote frequency
                Node node = keyMap.get(key);
                node.value = value;
                node.expiryTime = expiryTime;
                incrementFrequency(key, node);

            } else {
                // key is brand new
                if (keyMap.size() >= maxSize) {
                    // evict the least frequently used key
                    // minFreq always points to the correct bucket
                    LinkedHashSet<String> minFreqBucket = freqMap.get(minFreq);

                    // first key in the LinkedHashSet is the oldest at this frequency
                    String evictedKey = minFreqBucket.iterator().next();
                    minFreqBucket.remove(evictedKey);
                    keyMap.remove(evictedKey);
                    System.out.println("LFU EVICTION: Evicting key [" + evictedKey + "] at frequency " + minFreq);
                }

                // insert new key at frequency 1
                Node newNode = new Node(value, 1, expiryTime);
                keyMap.put(key, newNode);
                freqMap
                        .computeIfAbsent(1, k -> new LinkedHashSet<>())
                        .add(key);

                // new keys always start at frequency 1
                // so minFreq resets to 1
                minFreq = 1;
            }
        } finally {
            lock.unlock();
        }
    }


    @Override
    public int getCurrentSize() {
        lock.lock();
        try {
            return keyMap.size();
        } finally {
            lock.unlock();
        }
    }


    @Override
    public void delete(String key) {
        lock.lock();
        try {
            Node node = keyMap.get(key);
            if (node != null) {
                keyMap.remove(key);
                freqMap.get(node.frequency).remove(key);
                System.out.println("LFU DELETE: Removed key [" + key + "]");
            }
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void evictExpiredKeys() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            System.out.println("LFU SCHEDULED EVICTION: Scanning for expired keys...");

            // collect expired keys first, then remove
            // avoids ConcurrentModificationException from modifying
            // keyMap while iterating over it
            java.util.List<String> expiredKeys = new java.util.ArrayList<>();

            for (Map.Entry<String, Node> entry : keyMap.entrySet()) {
                Node node = entry.getValue();
                if (node.expiryTime != Long.MAX_VALUE && now > node.expiryTime) {
                    expiredKeys.add(entry.getKey());
                }
            }

            for (String key : expiredKeys) {
                Node node = keyMap.get(key);
                freqMap.get(node.frequency).remove(key);
                keyMap.remove(key);
                System.out.println("LFU SCHEDULED EVICTION: Removed expired key [" + key + "]");
            }

        } finally {
            lock.unlock();
        }
    }
}
