package com.gatewayrouter.loadbalancer;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;

@RestController
@RequestMapping("/api/v1/cache")
@CrossOrigin(origins = "*") // Ensures React App can always access the endpoints
public class GatewayController {

    private final ConsistentHashRouter hashRouter;
    private final RestTemplate restTemplate;
    
    // 1. Inject our new MongoDB Repository Executor
    private final CacheRepository cacheRepository;
    private final AsyncPersistenceService asyncPersistenceService;

  public GatewayController(
        ConsistentHashRouter hashRouter, 
        CacheRepository cacheRepository, 
        AsyncPersistenceService asyncPersistenceService) {
        
    this.hashRouter = hashRouter;
    this.cacheRepository = cacheRepository;
    this.asyncPersistenceService = asyncPersistenceService; 
    
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
requestFactory.setConnectTimeout(2000); // 2 seconds to establish connection
requestFactory.setReadTimeout(3000);    // 3 seconds to wait for response
    this.restTemplate = new RestTemplate(requestFactory);


}

    // Proxy PUT requests (Write-Through Implementation)
@PutMapping("/{key}")
public ResponseEntity<String> putData(
        @PathVariable String key,
        @RequestBody String value,
        @RequestParam(defaultValue = "0") long ttl,
        @RequestParam(defaultValue = "1") int replicas,
        @RequestParam(defaultValue = "weak") String consistency) {

    List<String> targetNodes = hashRouter.routeKeyWithReplicas(key, replicas);

    if (targetNodes.isEmpty()) {
        return ResponseEntity.internalServerError()
            .body("No live cache nodes available.");
    }

    long expiryTime = (ttl <= 0)
        ? Long.MAX_VALUE
        : System.currentTimeMillis() + (ttl * 1000);

    String primaryNode = targetNodes.get(0);
    List<String> replicaNodes = targetNodes.subList(1, targetNodes.size());

    if (consistency.equalsIgnoreCase("strong")) {
        // write to all nodes synchronously — wait for every confirmation
        List<String> failedNodes = new java.util.ArrayList<>();

        for (String nodeUrl : targetNodes) {
            try {
                restTemplate.put(
                    nodeUrl + "/api/v1/cache/" + key + "?ttl=" + ttl,
                    value
                );
                System.out.println("STRONG WRITE: confirmed by " + nodeUrl);
            } catch (Exception e) {
                System.out.println("STRONG WRITE FAILED on " + nodeUrl + ": " + e.getMessage());
                failedNodes.add(nodeUrl);
            }
        }

        // persist to MongoDB
        asyncPersistenceService.saveToDatabase(key, value, expiryTime);

        if (failedNodes.isEmpty()) {
            return ResponseEntity.ok(
                "STRONG: Written to " + targetNodes.size() + " nodes " + targetNodes
            );
        } else {
            return ResponseEntity.status(207)
                .body("PARTIAL: Written to some nodes. Failed: " + failedNodes);
        }

    } else {
        // weak consistency — write to primary synchronously,
        // replicate to remaining nodes asynchronously
        try {
            restTemplate.put(
                primaryNode + "/api/v1/cache/" + key + "?ttl=" + ttl,
                value
            );
            System.out.println("WEAK WRITE: confirmed by primary " + primaryNode);
        } catch (Exception e) {
            System.out.println("WARNING: Primary node " + primaryNode + " is offline.");
        }

        // fire replica writes in background — don't block the response
        for (String replicaUrl : replicaNodes) {
            asyncPersistenceService.replicateToNode(replicaUrl, key, value, ttl);
        }

        // persist to MongoDB
        asyncPersistenceService.saveToDatabase(key, value, expiryTime);

        return ResponseEntity.ok(
            "WEAK: Written to primary " + primaryNode +
            (replicaNodes.isEmpty() ? "" : " | Replicating async to " + replicaNodes)
        );
    }
}

    // Proxy GET requests (Cache-Aside with Auto-Recovery Implementation)
    @GetMapping("/{key}")
    public ResponseEntity<String> getData(@PathVariable String key) {
        String targetNodeUrl = hashRouter.routeKey(key);
        
        if (targetNodeUrl == null) {
            return fetchFromDatabaseFallback(key);
        }

        String fullUrl = targetNodeUrl + "/api/v1/cache/" + key;
        System.out.println("Routing GET for key [" + key + "] to -> " + targetNodeUrl);

        try {
            // Attempt to fetch from high-speed RAM Cache Node (Cache Hit path)
            ResponseEntity<String> response = restTemplate.getForEntity(fullUrl, String.class);
            System.out.println("CACHE HIT: Retrieved [" + key + "] from in-memory cluster node.");
            return response;
        } catch (Exception e) {
            // 3. CACHE MISS / NODE CRASH EXCEPTION: Intercept error and dive into disk storage
            System.out.println("CACHE MISS / SERVER DOWN at " + targetNodeUrl + ". Initiating MongoDB failover...");
            return fetchFromDatabaseFallback(key);
        }
    }


    @DeleteMapping("/{key}")
public ResponseEntity<String> deleteData(@PathVariable String key) {
    String targetNodeUrl = hashRouter.routeKey(key);
    if (targetNodeUrl == null) {
        return ResponseEntity.internalServerError().body("No live cache nodes available.");
    }

    // Delete from the cache node
    try {
        restTemplate.delete(targetNodeUrl + "/api/v1/cache/" + key);
    } catch (Exception e) {
        System.out.println("WARNING: Could not delete from node " + targetNodeUrl);
    }

    // Delete from MongoDB too
    cacheRepository.deleteById(key);

    return ResponseEntity.ok("Key [" + key + "] deleted from cache and MongoDB.");
}

    // Helper method to look into MongoDB and "warm up" the cache ring
private ResponseEntity<String> fetchFromDatabaseFallback(String key) {
    return cacheRepository.findById(key)
        .map(entry -> {
            long now = System.currentTimeMillis();

            // skip entries whose TTL already lapsed while sitting in MongoDB
            if (entry.getExpiryTime() != Long.MAX_VALUE && now > entry.getExpiryTime()) {
                System.out.println("DATABASE MISS: Key [" + key + "] TTL has expired on disk.");
                return ResponseEntity.notFound().<String>build();
            }

            System.out.println("DATABASE HIT: Recovered key [" + key + "] from MongoDB.");

            String freshTargetNode = hashRouter.routeKey(key);
            if (freshTargetNode != null) {
                // carry over remaining TTL, not a fresh 0 (which would make it permanent)
                long remainingTtl = (entry.getExpiryTime() == Long.MAX_VALUE)
                    ? 0
                    : Math.max(1, (entry.getExpiryTime() - now) / 1000);
                try {
                    restTemplate.put(
                        freshTargetNode + "/api/v1/cache/" + key + "?ttl=" + remainingTtl,
                        entry.getValue()
                    );
                } catch (Exception ex) {
                    System.out.println("Cache warm-up delayed: live node unreachable.");
                }
            }
            return ResponseEntity.ok(entry.getValue());
        })
        .orElseGet(() -> {
            System.out.println("CRITICAL: Key [" + key + "] not found in RAM or MongoDB.");
            return ResponseEntity.notFound().build();
        });
}
   
    @GetMapping("/cluster-status")
    public ResponseEntity<String> getClusterStatus() {
        List<String> nodes = ConsistentHashRouter.PHYSICAL_NODES;
        StringBuilder jsonBuilder = new StringBuilder("{");
        
        for (int i = 0; i < nodes.size(); i++) {
            String nodeName = "node" + (i + 1);
            try {
                String health = restTemplate.getForObject(nodes.get(i) + "/api/v1/cache/health", String.class);
                jsonBuilder.append("\"").append(nodeName).append("\": ").append(health);
            } catch (Exception e) {
                jsonBuilder.append("\"").append(nodeName).append("\": {\"status\": \"DOWN\", \"keysStored\": 0}");
            }
            if (i < nodes.size() - 1) jsonBuilder.append(", ");
        }
        jsonBuilder.append("}");
        return ResponseEntity.ok(jsonBuilder.toString());
    }

    @PostMapping("/kill/{nodeName}")
    public ResponseEntity<String> killNode(@PathVariable String nodeName) {
        if (!nodeName.matches("node[1-3]")) {
            return ResponseEntity.badRequest().body("Invalid node name. Choose node1, node2, or node3.");
        }
        try {
            String realContainerName = "distributedcache-" + nodeName + "-1";
            System.out.println("CRITICAL: Chaos Monkey triggered! Terminating container: " + realContainerName);
            
            String[] command = {"docker", "stop", realContainerName};
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                int nodeNumber = Integer.parseInt(nodeName.replace("node", ""));
                String physicalNodeUrl = ConsistentHashRouter.PHYSICAL_NODES.get(nodeNumber - 1);
                hashRouter.removeNode(physicalNodeUrl);
                return ResponseEntity.ok(realContainerName + " successfully terminated.");
            } else {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getErrorStream())
                );
                StringBuilder errorLog = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    errorLog.append(line).append(" ");
                }
                System.out.println("DOCKER CLI ERROR OUT: " + errorLog.toString());
                return ResponseEntity.internalServerError().body("Docker Error: " + errorLog.toString());
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    
    @PostMapping("/start/{nodeName}")
public ResponseEntity<String> startNode(@PathVariable String nodeName) {
    if (!nodeName.matches("node[1-3]")) {
        return ResponseEntity.badRequest().body("Invalid node name. Choose node1, node2, or node3.");
    }
    try {
        String realContainerName = "distributedcache-" + nodeName + "-1";
        System.out.println("RECOVERY: Flipping power switch ON for container: " + realContainerName);

        String[] command = {"docker", "start", realContainerName};
        Process process = Runtime.getRuntime().exec(command);
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            int nodeNumber = Integer.parseInt(nodeName.replace("node", ""));
            String physicalNodeUrl = ConsistentHashRouter.PHYSICAL_NODES.get(nodeNumber - 1); // Fix 1

            
            System.out.println("-> " + nodeName + " re-entered Hash Ring. Waiting for Spring Boot runtime initialization...");

            boolean isServerReady = false;
            int maxRetries = 10;

            while (maxRetries > 0 && !isServerReady) {
                try {
                     restTemplate.getForObject(physicalNodeUrl + "/api/v1/cache/health", String.class); // Fix 2
                    hashRouter.addNode(physicalNodeUrl);
                   
                    isServerReady = true;
                    System.out.println("-> " + nodeName + " is fully awake and listening on port " + (8080 + nodeNumber));
                } catch (Exception e) {
                    maxRetries--;
                    System.out.println("-> " + nodeName + " Tomcat port not ready yet. Retrying in 1s... (Attempts left: " + maxRetries + ")");
                    Thread.sleep(1000);
                }
            }

            int restoredKeysCounter = 0;
            if (isServerReady) {
                java.util.List<CacheEntry> databaseBackup = cacheRepository.findAll();
                long now = System.currentTimeMillis();

                for (CacheEntry entry : databaseBackup) {
                    // Fix 3: skip keys whose TTL already lapsed while sitting in MongoDB
                    if (entry.getExpiryTime() != Long.MAX_VALUE && now > entry.getExpiryTime()) {
                        continue;
                    }

                    String correctNodeForKey = hashRouter.routeKey(entry.getKey());
                    if (physicalNodeUrl.equals(correctNodeForKey)) {
                        try {
                            // Fix 3: carry over remaining TTL instead of making keys permanent
                            long remainingTtl = (entry.getExpiryTime() == Long.MAX_VALUE)
                                ? 0
                                : Math.max(1, (entry.getExpiryTime() - now) / 1000);

                            restTemplate.put(
                                physicalNodeUrl + "/api/v1/cache/" + entry.getKey() + "?ttl=" + remainingTtl,
                                entry.getValue()
                            );
                            restoredKeysCounter++;
                        } catch (Exception ex) {
                            System.out.println("Restoration warning: Failed to push key [" + entry.getKey() + "] due to: " + ex.getMessage());
                        }
                    }
                }
            } else {
                System.out.println("CRITICAL: Container started but Spring Boot runtime failed to initialize within time limit.");
                return ResponseEntity.internalServerError().body("Container power on succeeded, but hydration timed out waiting for Spring Boot.");
            }

            System.out.println("SUCCESS: Hydration complete. Restored " + restoredKeysCounter + " keys back into " + nodeName + " RAM.");
            return ResponseEntity.ok(realContainerName + " successfully recovered. Restored " + restoredKeysCounter + " keys from MongoDB disk back into hot RAM.");

        } else {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()));
            StringBuilder errorLog = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) { errorLog.append(line).append(" "); }
            return ResponseEntity.internalServerError().body("Docker Error: " + errorLog.toString());
        }
    } catch (Exception e) {
        return ResponseEntity.internalServerError().body("Error executing recovery: " + e.getMessage());
    }
}



@Scheduled(fixedDelay = 5000) // runs every 5 seconds
public void monitorClusterHealth() {
    for (String nodeUrl : ConsistentHashRouter.PHYSICAL_NODES) {
        try {
            restTemplate.getForObject(nodeUrl + "/api/v1/cache/health", String.class);
            // node responded — make sure it's in the ring
            hashRouter.addNode(nodeUrl);
        } catch (Exception e) {
            // node unreachable — remove it from the ring silently
            hashRouter.removeNode(nodeUrl);
            System.out.println("HEALTH MONITOR: " + nodeUrl + " is unreachable. Removed from ring.");
        }
    }
}
}