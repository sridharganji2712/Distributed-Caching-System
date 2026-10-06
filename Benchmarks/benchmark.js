import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'

// Custom metrics
const cacheHits    = new Counter('cache_hits')
const cacheMisses  = new Counter('cache_misses')
const hitRate      = new Rate('cache_hit_rate')
const weakLatency  = new Trend('weak_consistency_latency')
const strongLatency = new Trend('strong_consistency_latency')

const GATEWAY = 'http://localhost:8080/api/v1/cache'

export const options = {
  scenarios: {

    // Scenario 1: Warm up
    warm_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 10 },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '5s',
      tags: { scenario: 'warm_up' },
    },

    // Scenario 2: Read heavy (cache hit testing)
    read_heavy: {
      executor: 'ramping-vus',
      startTime: '30s',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '1m',  target: 100 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '5s',
      tags: { scenario: 'read_heavy' },
    },

    // Scenario 3: Write heavy
    write_heavy: {
      executor: 'ramping-vus',
      startTime: '2m30s',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '1m',  target: 50 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '5s',
      tags: { scenario: 'write_heavy' },
    },

    // Scenario 4: Replication comparison
    replication_test: {
      executor: 'ramping-vus',
      startTime: '4m30s',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 30 },
        { duration: '1m',  target: 30 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '5s',
      tags: { scenario: 'replication_test' },
    },

  },

  thresholds: {
    // overall
    http_req_duration:           ['p(95)<500', 'p(99)<1000'],
    http_req_failed:             ['rate<0.01'],
    // custom
    cache_hit_rate:              ['rate>0.5'],
    weak_consistency_latency:    ['p(95)<200'],
    strong_consistency_latency:  ['p(95)<600'],
  },
}

// seed keys before test starts
export function setup() {
  console.log('Seeding cache with 50 keys...')
  for (let i = 0; i < 50; i++) {
    http.put(
      `${GATEWAY}/bench_key_${i}?ttl=600`,
      `bench_value_${i}`,
      { headers: { 'Content-Type': 'text/plain' } }
    )
  }
  console.log('Seeding complete.')
}

export default function () {
  const scenario = __ENV.K6_SCENARIO || 'read_heavy'
  const rand = Math.random()

  // ── Read Heavy scenario ──────────────────────────────────
  if (__ITER % 4 === 0 || scenario === 'read_heavy') {
    const keyId = Math.floor(Math.random() * 50)
    const res = http.get(`${GATEWAY}/bench_key_${keyId}`)

    const hit = check(res, {
      'GET status 200':             (r) => r.status === 200,
      'GET response time < 100ms':  (r) => r.timings.duration < 100,
    })

    if (res.status === 200) {
      cacheHits.add(1)
      hitRate.add(true)
    } else {
      cacheMisses.add(1)
      hitRate.add(false)
    }
  }

  // ── Write Heavy scenario ─────────────────────────────────
  else if (rand < 0.6) {
    const keyId = Math.floor(Math.random() * 1000)
    const res = http.put(
      `${GATEWAY}/bench_key_${keyId}?ttl=120`,
      `value_${keyId}_${Date.now()}`,
      { headers: { 'Content-Type': 'text/plain' } }
    )

    check(res, {
      'PUT status 200':            (r) => r.status === 200,
      'PUT response time < 200ms': (r) => r.timings.duration < 200,
    })
  }

  // ── Weak consistency replication ─────────────────────────
  else if (rand < 0.8) {
    const keyId = Math.floor(Math.random() * 1000)
    const res = http.put(
      `${GATEWAY}/bench_key_${keyId}?ttl=120&replicas=2&consistency=weak`,
      `value_${keyId}`,
      { headers: { 'Content-Type': 'text/plain' } }
    )

    weakLatency.add(res.timings.duration)
    check(res, {
      'WEAK PUT status 200':            (r) => r.status === 200,
      'WEAK PUT response time < 300ms': (r) => r.timings.duration < 300,
    })
  }

  // ── Strong consistency replication ───────────────────────
  else {
    const keyId = Math.floor(Math.random() * 1000)
    const res = http.put(
      `${GATEWAY}/bench_key_${keyId}?ttl=120&replicas=2&consistency=strong`,
      `value_${keyId}`,
      { headers: { 'Content-Type': 'text/plain' } }
    )

    strongLatency.add(res.timings.duration)
    check(res, {
      'STRONG PUT status 200':            (r) => r.status === 200,
      'STRONG PUT response time < 600ms': (r) => r.timings.duration < 600,
    })
  }

  sleep(0.1)
}

export function handleSummary(data) {
  const metrics = data.metrics

  const summary = `
╔══════════════════════════════════════════════════════════════╗
║           DISTRIBUTED CACHE BENCHMARK RESULTS                ║
╠══════════════════════════════════════════════════════════════╣
║ THROUGHPUT                                                   ║
║   Total Requests : ${String(metrics.http_reqs?.values?.count || 0).padEnd(38)}║
║   Requests/sec   : ${String((metrics.http_reqs?.values?.rate || 0).toFixed(2)).padEnd(38)}║
╠══════════════════════════════════════════════════════════════╣
║ LATENCY (overall)                                            ║
║   avg            : ${String((metrics.http_req_duration?.values?.avg || 0).toFixed(2) + 'ms').padEnd(38)}║
║   p(90)          : ${String((metrics.http_req_duration?.values['p(90)'] || 0).toFixed(2) + 'ms').padEnd(38)}║
║   p(95)          : ${String((metrics.http_req_duration?.values['p(95)'] || 0).toFixed(2) + 'ms').padEnd(38)}║
║   p(99)          : ${String((metrics.http_req_duration?.values['p(99)'] || 0).toFixed(2) + 'ms').padEnd(38)}║
╠══════════════════════════════════════════════════════════════╣
║ CONSISTENCY COMPARISON                                       ║
║   Weak  p(95)    : ${String((metrics.weak_consistency_latency?.values['p(95)'] || 0).toFixed(2) + 'ms').padEnd(38)}║
║   Strong p(95)   : ${String((metrics.strong_consistency_latency?.values['p(95)'] || 0).toFixed(2) + 'ms').padEnd(38)}║
╠══════════════════════════════════════════════════════════════╣
║ CACHE EFFECTIVENESS                                          ║
║   Cache Hits     : ${String(metrics.cache_hits?.values?.count || 0).padEnd(38)}║
║   Cache Misses   : ${String(metrics.cache_misses?.values?.count || 0).padEnd(38)}║
║   Hit Rate       : ${String(((metrics.cache_hit_rate?.values?.rate || 0) * 100).toFixed(1) + '%').padEnd(38)}║
╠══════════════════════════════════════════════════════════════╣
║ RELIABILITY                                                  ║
║   Failed Reqs    : ${String(((metrics.http_req_failed?.values?.rate || 0) * 100).toFixed(2) + '%').padEnd(38)}║
╚══════════════════════════════════════════════════════════════╝
`
  console.log(summary)

  // also write to a file for record keeping
  return {
    'benchmark/results.txt': summary,
    stdout: summary,
  }
}