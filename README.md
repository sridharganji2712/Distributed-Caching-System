# Distributed Caching System

A horizontally scalable, in-memory distributed cache built with **Spring Boot**. A gateway routes every request to one of several cache nodes using **consistent hashing**, each node evicts entries with a pluggable **LRU / LFU** policy, and writes are persisted to **MongoDB** asynchronously so data survives restarts. A **React dashboard** visualizes the cluster, and the whole system runs on **Docker Compose** or **Kubernetes**.

---

## Table of Contents

1. The Problem
2. The Solution
3. Architecture
4. Key Concepts
5. Tech Stack
6. Project Structure
7. Environment Setup
8. Running the System
9. API Reference
10. Configuration

---

## The Problem

A single cache server has three hard limits:

- **Capacity:** one machine's RAM caps how much data you can cache.
- **Throughput:** one process can only serve so many requests per second.
- **Availability:** if that server restarts or dies, the entire cache is lost and every request falls through to the database at once.

The obvious fix is to run several cache servers, but that creates new problems:

- **Which server holds which key?** Clients need a deterministic way to find a key's location.
- **What happens when a server is added or removed?** With naive `hash(key) % N` routing, changing `N` remaps almost every key and causes a mass cache miss.
- **What happens when memory fills up?** Something has to decide which entries to throw away.
- **What happens on restart?** An in-memory cache is empty after a restart unless data is persisted somewhere.

## The Solution

This project addresses each problem with a dedicated component:

| Problem | Solution in this project |
|---|---|
| Capacity and throughput | Multiple cache nodes, each holding a shard of the keyspace |
| Locating a key | A gateway that routes via a **consistent hash ring** |
| Remapping when nodes change | **Virtual nodes** on the ring, so only about `1/N` of keys move |
| Bounded memory | Per-node **LRU or LFU eviction** |
| Durability | **Asynchronous persistence** to MongoDB so the write path stays fast |
| Operability | Docker Compose, Kubernetes manifests, and a monitoring dashboard |

## Architecture

```
                         +---------------------------+
                         |  React Dashboard (Vite)   |
                         +-------------+-------------+
                                       |
                                       v
 Client ───────────►  +----------------------------------+
                      |   Gateway (loadbalancer module)  |
                      |                                  |
                      |   ConsistentHashRouter           |
                      |   GatewayController              |
                      |   AsyncPersistenceService        |
                      +---+----------+----------+--------+
                          |          |          |            \  async write
                          v          v          v             \
                    +---------+ +---------+ +---------+        v
                    | Cache   | | Cache   | | Cache   |   +---------+
                    | Node 1  | | Node 2  | | Node N  |   | MongoDB |
                    | LRU/LFU | | LRU/LFU | | LRU/LFU |   +---------+
                    +---------+ +---------+ +---------+
```

**Request flow (read):**

1. The client sends `GET key` to the gateway.
2. The gateway hashes the key and finds the owning node on the ring.
3. The request is forwarded to that cache node.
4. The node returns the value on a hit. On a miss, the gateway can fall back to the persisted copy in MongoDB.

**Request flow (write):**

1. The client sends `PUT key value` to the gateway.
2. The gateway routes it to the owning cache node, which stores it and runs eviction if the node is full.
3. The write is handed to a background thread pool that persists it to MongoDB, so the client does not wait for the database.

## Key Concepts

### Consistent hashing with virtual nodes

Both servers and keys are hashed onto a circular space. A key belongs to the first server found moving clockwise from the key's position. The ring is stored in a sorted map (`TreeMap`) and looked up with `ceilingEntry()`, which gives `O(log n)` routing.

Each physical node is placed on the ring many times as **virtual nodes**. This evens out the load and means that when a node joins or leaves, only a small fraction of keys are remapped instead of nearly all of them.

### Eviction policies (LRU and LFU)

When a node reaches capacity it must remove an entry:

- **LRU (Least Recently Used):** evicts the entry that was accessed longest ago. Works well when recent data is likely to be reused.
- **LFU (Least Frequently Used):** evicts the entry with the fewest accesses. Works well when some keys are consistently hot.

Both are implemented behind a common `CacheEvictionService` interface, so the policy can be swapped without touching the rest of the node.

### Asynchronous persistence

Writing to MongoDB on every request would make the cache as slow as the database. Instead, `AsyncPersistenceService` runs on a dedicated executor (see `AsyncConfig`) so the client's response is not blocked by disk I/O. The trade-off is a short window in which an acknowledged write has not yet reached MongoDB (see [Design Decisions](#design-decisions-and-trade-offs)).

## Tech Stack

| Layer | Technology |
|---|---|
| Cache nodes and gateway | Java, Spring Boot, Maven |
| Persistence | MongoDB |
| Dashboard | React, Vite |
| Containers | Docker, Docker Compose |
| Orchestration | Kubernetes (manifests in `K8s/`) |
| Benchmarking | Node.js script |

## Project Structure

```
Distributed-Caching-System/
├── cachenode/                  # Cache node service
│   └── src/main/java/.../cachenode/
│       ├── CachenodeApplication.java
│       ├── CacheController.java          # REST endpoints of a node
│       ├── CacheConfig.java              # capacity / policy configuration
│       ├── CacheEvictionService.java     # eviction interface
│       ├── LRUCacheEvictionService.java  # LRU implementation
│       └── LFUCacheEvictionService.java  # LFU implementation
├── loadbalancer/               # Gateway service
│   └── src/main/java/.../loadbalancer/
│       ├── LoadbalancerApplication.java
│       ├── GatewayController.java        # public API
│       ├── ConsistentHashRouter.java     # hash ring + virtual nodes
│       ├── AsyncPersistenceService.java  # background writes to MongoDB
│       ├── AsyncConfig.java              # thread pool for async work
│       ├── CacheEntry.java               # persisted document
│       └── CacheRepository.java          # Spring Data MongoDB repository
├── DistributedCacheDashboard/  # React + Vite monitoring UI
├── K8s/                        # Kubernetes manifests
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── cachenode/              # deployment + service
│   ├── gateway/                # deployment + service
│   └── mongodb/                # deployment + PVC + service
├── Benchmarks/
│   └── benchmark.js            # load-generation script
└── docker-compose.yml          # one-command local cluster
```

## Environment Setup

### Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 17 or newer (match `java.version` in `pom.xml`) | Running or building the services without Docker |
| Maven | 3.9+ (or use the bundled `mvnw`) | Building the services |
| Node.js | 18 or newer (with npm) | Dashboard and benchmark script |
| Docker Desktop | Latest | Docker Compose and Kubernetes images |
| MongoDB | 6+ (only if running without Docker) | Persistence |
| kubectl and minikube | Latest (optional) | Kubernetes deployment |

Check your installs:

```bash
java -version
node -v
npm -v
docker --version
docker compose version
```

### Windows notes

- Use **PowerShell** or **CMD**. The bundled wrapper is `mvnw.cmd`, so run `mvnw.cmd clean package` instead of `./mvnw`.
- Docker Desktop with the **WSL 2 backend** is recommended.
- If Git prints `LF will be replaced by CRLF`, it is harmless. To silence it, run `git config core.autocrlf true`.

### Clone the repository

```bash
git clone https://github.com/sridharganji2712/Distributed-Caching-System.git
cd Distributed-Caching-System
```

## Running the System

### Option 1: Docker Compose (recommended)

Starts MongoDB, the cache nodes, and the gateway together.

```bash
docker compose up --build
```

Stop and clean up:

```bash
docker compose down
```

### Option 2: Run locally without Docker

1. **Start MongoDB** on its default port (27017), or point the gateway at your instance in `loadbalancer/src/main/resources/application.properties`.

2. **Start one or more cache nodes**, each on a different port:

   ```bash
   cd cachenode
   mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
   ```

   Repeat in new terminals with `8082`, `8083`, and so on.

3. **Register the nodes with the gateway** in `loadbalancer/src/main/resources/application.properties`, then start it:

   ```bash
   cd loadbalancer
   mvnw.cmd spring-boot:run
   ```

### Option 3: Kubernetes (minikube)

```bash
minikube start

kubectl apply -f K8s/namespace.yaml
kubectl apply -f K8s/configmap.yaml
kubectl apply -f K8s/mongodb/
kubectl apply -f K8s/cachenode/
kubectl apply -f K8s/gateway/

kubectl get pods -n <namespace>
```

If you use locally built images, build them against minikube's Docker daemon first (`minikube docker-env`) so the cluster can find them.

### Dashboard

```bash
cd DistributedCacheDashboard
npm install
npm run dev
```

Open the URL Vite prints (typically `http://localhost:5173`).

## API Reference

> Verify the paths below against `GatewayController.java` and adjust if they differ.

| Method | Endpoint | Description |

| `PUT` | `/cache/{key}` | Store a value for a key |
| `GET` | `/cache/{key}` | Retrieve the value for a key |
| `DELETE` | `/cache/{key}` | Remove a key |

Example (replace the port with your gateway port):

```bash
curl -X PUT  http://localhost:8080/cache/user:1 -H "Content-Type: application/json" -d "{\"value\": \"Alice\"}"
curl         http://localhost:8080/cache/user:1
curl -X DELETE http://localhost:8080/cache/user:1
```

## Configuration

| Setting | Location | Purpose |

| Cache capacity and eviction policy | `cachenode/.../CacheConfig.java` and `application.properties` | Max entries per node, LRU vs LFU |
| Node list | `loadbalancer/.../application.properties` | Cache nodes the gateway routes to |
| Virtual nodes per server | `ConsistentHashRouter.java` | Ring smoothness |
| MongoDB connection | `loadbalancer/.../application.properties` | Persistence target |
| Async thread pool | `AsyncConfig.java` | Persistence concurrency |
| Kubernetes settings | `K8s/configmap.yaml` | Shared environment values |

## Author

**Ganji Sridhar Babu**: [GitHub](https://github.com/sridharganji2712)
