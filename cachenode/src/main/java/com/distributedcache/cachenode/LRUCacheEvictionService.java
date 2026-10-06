package com.distributedcache.cachenode;


import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;


public class LRUCacheEvictionService implements CacheEvictionService {

    //private static final int MAX_SIZE = 3; // Keep low for easy chaos testing
    private final ConcurrentHashMap<String, Node> cacheMap = new ConcurrentHashMap<>();

    // Explicit lock to safeguard the Doubly Linked List structural pointers exclusively
    private final ReentrantLock pointerLock = new ReentrantLock();

    private Node head = null;
    private Node tail = null;


    private final int maxSize;

    // add this constructor
    public LRUCacheEvictionService(int maxSize) {
        this.maxSize = maxSize;
    }
    // Custom structural definition for the Doubly Linked List Node



    private static class Node {
        String key;
        String value;
        long expiryTime;
        Node prev;
        Node next;
        boolean unlinked = false; // guards against double-removal corrupting head/tail

        Node(String key, String value, long expiryTime) {
            this.key = key;
            this.value = value;
            this.expiryTime = expiryTime;
        }
    }

    /**
     * O(1) Fetch operation with high-concurrency non-blocking reads.
     */
    public String get(String key) {
        Node[] expiredNode = new Node[1];
        Node node = cacheMap.computeIfPresent(key, (k, existing) -> {
            if (System.currentTimeMillis() > existing.expiryTime) {
                expiredNode[0] = existing;
                return null; // atomically removes the entry
            }
            return existing;
        });

        if (node == null) {
            if (expiredNode[0] != null) {
                pointerLock.lock();
                try {
                    removeNode(expiredNode[0]); // unlink from the LRU list
                } finally {
                    pointerLock.unlock();
                }
            }
            return null;
        }

        pointerLock.lock();
        try {
            removeNode(node);
            addToHead(node);
        } finally {
            pointerLock.unlock();
        }

        return node.value;
    }

    /**
     * O(1) Upsert operation using Segment-level atomic computations and isolated pointer locks.
     */
    public void put(String key, String value, long ttlInSeconds) {
        long expiryTime = (ttlInSeconds <= 0)
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + (ttlInSeconds * 1000);

        // Fast path: key already exists — single-key atomic update, no eviction needed
        Node updatedNode = cacheMap.computeIfPresent(key, (k, existingNode) -> {
            existingNode.value = value;
            existingNode.expiryTime = expiryTime;
            return existingNode;
        });

        if (updatedNode != null) {
            pointerLock.lock();
            try {
                removeNode(updatedNode);
                addToHead(updatedNode);
            } finally {
                pointerLock.unlock();
            }
            return;
        }

        // Slow path: key is new — needs eviction check, must not be inside compute()
        pointerLock.lock();
        try {
            // Re-check: another thread may have inserted this key in the gap
            // between computeIfPresent returning null and us acquiring pointerLock
            Node racedNode = cacheMap.get(key);
            if (racedNode != null) {
                racedNode.value = value;
                racedNode.expiryTime = expiryTime;
                removeNode(racedNode);
                addToHead(racedNode);
                return;
            }

            if (cacheMap.size() >= maxSize) {
                Node tailNode = popTail();
                if (tailNode != null) {
                    cacheMap.remove(tailNode.key); // now safe — outside compute()
                    System.out.println("LRU EVICTION: Evicting key [" + tailNode.key + "]");
                }
            }

            Node newNode = new Node(key, value, expiryTime);
            addToHead(newNode);
            cacheMap.put(key, newNode); // plain put, not inside any compute callback
        } finally {
            pointerLock.unlock();
        }
    }

    public void delete(String key) {
        Node[] removed = new Node[1];

        cacheMap.computeIfPresent(key, (k, existing) -> {
            removed[0] = existing;
            return null; // atomically removes from map
        });

        if (removed[0] != null) {
            pointerLock.lock();
            try {
                removeNode(removed[0]);
            } finally {
                pointerLock.unlock();
            }
            System.out.println("DELETE: Removed key [" + key + "] from cache.");
        }
    }

    // --- Core Doubly Linked List Pointer Adjustments ($O(1)$ Complexity under Lock Protection) ---

    private void addToHead(Node node) {
        node.next = head;
        node.prev = null;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = head;
        }
        node.unlinked = false; // node is back in the list, reset the flag
    }

    private void removeNode(Node node) {
        if (node.unlinked) {
            return; // already detached, skip to avoid corrupting head/tail
        }

        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }

        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }

        node.next = null;
        node.prev = null;
        node.unlinked = true;
    }

    private Node popTail() {
        Node res = tail;
        if (tail != null) {
            removeNode(tail);
        }
        return res;
    }

    // Dynamic telemetry access utility
    public int getCurrentSize() {
        return cacheMap.size();
    }


    @Scheduled(fixedDelay = 30000) // runs every 30 seconds after previous run completes
    public void evictExpiredKeys() {
        long now = System.currentTimeMillis();
        System.out.println("SCHEDULED EVICTION: Scanning for expired keys...");

        cacheMap.forEach((key, node) -> {
            if (node.expiryTime != Long.MAX_VALUE && now > node.expiryTime) {
                Node[] expired = new Node[1];

                cacheMap.computeIfPresent(key, (k, existing) -> {
                    if (System.currentTimeMillis() > existing.expiryTime) {
                        expired[0] = existing;
                        return null; // atomically removes from map
                    }
                    return existing; // a concurrent put() refreshed it, leave it alone
                });

                if (expired[0] != null) {
                    pointerLock.lock();
                    try {
                        removeNode(expired[0]);
                    } finally {
                        pointerLock.unlock();
                    }
                    System.out.println("SCHEDULED EVICTION: Removed expired key [" + key + "]");
                }
            }
        });
    }
}