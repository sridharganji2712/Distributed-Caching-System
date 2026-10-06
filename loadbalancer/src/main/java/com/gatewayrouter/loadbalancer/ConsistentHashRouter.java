package com.gatewayrouter.loadbalancer;

import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConsistentHashRouter {
    private final ConcurrentSkipListMap<Long, String> ring = new ConcurrentSkipListMap<>();
    private final Set<String> activeNodes = ConcurrentHashMap.newKeySet();

    private final int VIRTUAL_NODES=100;
    public static final List<String> PHYSICAL_NODES = List.of(
            "http://node1-service:8081",
            "http://node2-service:8082",
            "http://node3-service:8083"

    );


    public ConsistentHashRouter()
    {
        for (String node : PHYSICAL_NODES ) {
            addNode(node);
        }
    }

    public void addNode(String node)
    {
        if (activeNodes.contains(node)) {
        return; // already in ring, skip the 100 put operations
        }
        for (int i = 0; i < VIRTUAL_NODES; i++) {

            String virtualNodeName = node + "#" + i;
            long hash = hash(virtualNodeName);
            ring.put(hash, node); 
        }
        activeNodes.add(node);
    }

    public void removeNode(String node)
    {
        for(int i=0;i<VIRTUAL_NODES;i++)
        {
            ring.remove(hash(node + "#" + i));
        }
        activeNodes.remove(node);
    }

  public String routeKey(String key) {
    if (ring.isEmpty()) {
        return null;
    }

    long hash = hash(key);

    Long nodeHash = ring.ceilingKey(hash);
    if (nodeHash == null) {
        nodeHash = ring.firstKey(); 
    }

    return ring.get(nodeHash);
}

    public List<String> routeKeyWithReplicas(String key, int replicaCount) {
    List<String> selectedNodes = new java.util.ArrayList<>();

    if (ring.isEmpty()) {
        return selectedNodes;
    }

    // cap replicaCount to how many physical nodes are actually alive
    int effectiveCount = Math.min(replicaCount, activeNodes.size());

    long hash = hash(key);

    // start walking the ring clockwise from the key's hash position
    // tailMap gives us all entries >= hash, then we wrap around
    java.util.NavigableMap<Long, String> tail = ring.tailMap(hash, true);

    // combine tail + full ring for wrap-around
    Iterable<String> ringWalk = () -> new java.util.Iterator<String>() {
        java.util.Iterator<String> tailIter = tail.values().iterator();
        java.util.Iterator<String> fullIter = ring.values().iterator();
        boolean onTail = true;

        public boolean hasNext() {
            return tailIter.hasNext() || fullIter.hasNext();
        }

        public String next() {
            if (onTail && tailIter.hasNext()) {
                return tailIter.next();
            }
            onTail = false;
            return fullIter.next();
        }
    };

    // walk the ring and collect distinct physical nodes
    for (String node : ringWalk) {
        if (!selectedNodes.contains(node)) {
            selectedNodes.add(node);
        }
        if (selectedNodes.size() >= effectiveCount) {
            break;
        }
    }

    return selectedNodes;
}
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes());
            return ((long) (digest[3] & 0xFF) << 24) |
                    ((long) (digest[2] & 0xFF) << 16) |
                    ((long) (digest[1] & 0xFF) << 8) |
                    ((long) (digest[0] & 0xFF));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not supported", e);
        }
    }


}
