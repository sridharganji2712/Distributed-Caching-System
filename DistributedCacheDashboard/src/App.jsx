import { useState, useEffect } from 'react'
import axios from 'axios'
import './App.css'

const GATEWAY_URL = 'http://localhost:8080/api/v1/cache'
const NODE_NAMES = ['node1', 'node2', 'node3', 'node4', 'node5', 'node6']
const NODE_URLS = [
  'http://node1:8081', 'http://node2:8082', 'http://node3:8083',
  'http://node4:8084', 'http://node5:8085', 'http://node6:8086'
]

function App() {
  const [activeTab, setActiveTab] = useState('dashboard')
  const [key, setKey] = useState('')
  const [value, setValue] = useState('')
  const [ttl, setTtl] = useState('')
  const [replicas, setReplicas] = useState('1')
  const [consistency, setConsistency] = useState('weak')
  const [logs, setLogs] = useState(['System Ready...'])
  const [nodeLogs, setNodeLogs] = useState({
    node1: [], node2: [], node3: [],
    node4: [], node5: [], node6: []
  })
  const [clusterStatus, setClusterStatus] = useState(null)
  const [stats, setStats] = useState({ hits: 0, misses: 0 })

  const addLog = (message) => {
    const timestamp = new Date().toLocaleTimeString()
    setLogs(prev => [`[${timestamp}] ${message}`, ...prev].slice(0, 100))
  }

  const addNodeLog = (nodeName, message) => {
    const timestamp = new Date().toLocaleTimeString()
    setNodeLogs(prev => ({
      ...prev,
      [nodeName]: [`[${timestamp}] ${message}`, ...prev[nodeName]].slice(0, 50)
    }))
  }

  // parse which nodes were involved from gateway response text
  const parseNodesFromResponse = (responseText) => {
    const involved = []
    NODE_NAMES.forEach((name, index) => {
      if (responseText.includes(NODE_URLS[index])) {
        involved.push(name)
      }
    })
    return involved
  }

  // poll cluster health every 2 seconds
  useEffect(() => {
    const fetchHealth = async () => {
      try {
        const response = await axios.get(`${GATEWAY_URL}/cluster-status`)
        const parsed = typeof response.data === 'string'
          ? JSON.parse(response.data)
          : response.data
        setClusterStatus(parsed)
      } catch (error) {
        addLog('WARNING: Could not reach gateway for cluster status.')
      }
    }
    fetchHealth()
    const interval = setInterval(fetchHealth, 2000)
    return () => clearInterval(interval)
  }, [])

  const handlePut = async () => {
    if (!key || !value) {
      addLog('ERROR: Key and value are required.')
      return
    }
    try {
      addLog(`PUT | key: [${key}] | TTL: ${ttl || 'none'} | replicas: ${replicas} | consistency: ${consistency}`)
      const url = `${GATEWAY_URL}/${key}?ttl=${ttl || 0}&replicas=${replicas}&consistency=${consistency}`
      const response = await axios.put(url, value, {
        headers: { 'Content-Type': 'text/plain' }
      })
      addLog(`SUCCESS: ${response.data}`)
      const involvedNodes = parseNodesFromResponse(response.data)
      involvedNodes.forEach(node => {
        addNodeLog(node, `PUT key=[${key}] value=[${value}] ttl=${ttl || 0}s replicas=${replicas} consistency=${consistency}`)
      })
    } catch (error) {
      addLog(`ERROR: Failed to PUT key [${key}].`)
    }
  }

  const handleGet = async () => {
    if (!key) {
      addLog('ERROR: Key is required.')
      return
    }
    try {
      addLog(`GET | key: [${key}]...`)
      const response = await axios.get(`${GATEWAY_URL}/${key}`)
      addLog(`CACHE HIT: [${key}] = "${response.data}"`)
      setStats(prev => ({ ...prev, hits: prev.hits + 1 }))
    } catch (error) {
      if (error.response && error.response.status === 404) {
        addLog(`CACHE MISS: Key [${key}] not found.`)
        setStats(prev => ({ ...prev, misses: prev.misses + 1 }))
      } else {
        addLog(`ERROR: Failed to GET key [${key}].`)
      }
    }
  }

  const handleDelete = async () => {
    if (!key) {
      addLog('ERROR: Key is required.')
      return
    }
    try {
      addLog(`DELETE | key: [${key}]...`)
      const response = await axios.delete(`${GATEWAY_URL}/${key}`)
      addLog(`SUCCESS: ${response.data}`)
    } catch (error) {
      addLog(`ERROR: Failed to DELETE key [${key}].`)
    }
  }

  const handleKill = async (nodeName) => {
    try {
      addLog(`CHAOS: Sending kill signal to ${nodeName}...`)
      addNodeLog(nodeName, `KILL signal received`)
      const response = await axios.post(`${GATEWAY_URL}/kill/${nodeName}`)
      addLog(`CHAOS: ${response.data}`)
    } catch (error) {
      addLog(`ERROR: Failed to kill ${nodeName}.`)
    }
  }

  const handleStart = async (nodeName) => {
    try {
      addLog(`RECOVERY: Starting ${nodeName}...`)
      addNodeLog(nodeName, `START signal received`)
      const response = await axios.post(`${GATEWAY_URL}/start/${nodeName}`)
      addLog(`RECOVERY: ${response.data}`)
      addNodeLog(nodeName, `Node recovered successfully`)
    } catch (error) {
      addLog(`ERROR: Failed to start ${nodeName}.`)
    }
  }

  const totalRequests = stats.hits + stats.misses
  const hitRatio = totalRequests === 0 ? 0 : ((stats.hits / totalRequests) * 100).toFixed(1)

  const renderNodeCard = (nodeName, data) => {
    const isUp = data && data.status === 'UP'
    return (
      <div className={`node-card ${isUp ? 'healthy' : 'dead'}`} key={nodeName}>
        <div className="node-header">
          <span className={`status-light ${isUp ? 'green' : 'red'}`}></span>
          <h3>{nodeName.toUpperCase()}</h3>
        </div>
        <p>Status: <strong>{isUp ? 'ONLINE' : 'OFFLINE'}</strong></p>
        <p>Keys: <strong>{data ? data.keysStored : 0}</strong></p>
        <div className="card-buttons">
          <button className="btn-kill" onClick={() => handleKill(nodeName)} disabled={!isUp}>Kill</button>
          <button className="btn-start" onClick={() => handleStart(nodeName)} disabled={isUp}>Start</button>
        </div>
      </div>
    )
  }

  return (
    <div className="dashboard">
      <h1>Distributed Cache Command Center</h1>

      {/* Tab Navigation */}
      <div className="tab-nav">
        <button
          className={`tab-btn ${activeTab === 'dashboard' ? 'active' : ''}`}
          onClick={() => setActiveTab('dashboard')}
        >
          Dashboard
        </button>
        <button
          className={`tab-btn ${activeTab === 'logs' ? 'active' : ''}`}
          onClick={() => setActiveTab('logs')}
        >
          Node Logs
        </button>
      </div>

      {activeTab === 'dashboard' && (
        <>
          {/* Cluster Topology */}
          <div className="cluster-visualizer">
            <h2>Live Cluster Topology</h2>
            <div className="nodes-container">
              {clusterStatus ? (
                Object.keys(clusterStatus).map(node =>
                  renderNodeCard(node, clusterStatus[node])
                )
              ) : (
                <p>Connecting to cluster...</p>
              )}
            </div>
          </div>

          {/* Stats Panel */}
          <div className="stats-panel">
            <div className="stat-card">
              <h3>Cache Hits</h3>
              <p className="stat-number hits">{stats.hits}</p>
            </div>
            <div className="stat-card">
              <h3>Cache Misses</h3>
              <p className="stat-number misses">{stats.misses}</p>
            </div>
            <div className="stat-card">
              <h3>Total Requests</h3>
              <p className="stat-number">{totalRequests}</p>
            </div>
            <div className="stat-card wide">
              <h3>Hit Ratio</h3>
              <div className="ratio-bar-container">
                <div className="ratio-bar" style={{ width: `${hitRatio}%` }}></div>
              </div>
              <p className="ratio-label">{hitRatio}% hit rate</p>
            </div>
          </div>

          {/* Cache Operations */}
          <div className="control-panel">
            <h2>Cache Operations</h2>
            <div className="input-group">
              <input
                type="text"
                placeholder="Key"
                value={key}
                onChange={(e) => setKey(e.target.value)}
              />
              <input
                type="text"
                placeholder="Value"
                value={value}
                onChange={(e) => setValue(e.target.value)}
              />
              <input
                type="number"
                placeholder="TTL (seconds)"
                value={ttl}
                onChange={(e) => setTtl(e.target.value)}
              />
              <input
                type="number"
                placeholder="Replicas (1-6)"
                value={replicas}
                min="1"
                max="6"
                onChange={(e) => setReplicas(e.target.value)}
              />
              <select value={consistency} onChange={(e) => setConsistency(e.target.value)}>
                <option value="weak">Weak</option>
                <option value="strong">Strong</option>
              </select>
            </div>
            <div className="button-group">
              <button onClick={handlePut} className="btn-put">PUT</button>
              <button onClick={handleGet} className="btn-get">GET</button>
              <button onClick={handleDelete} className="btn-delete">DELETE</button>
            </div>
          </div>

          {/* Activity Log */}
          <div className="terminal">
            <h2>Activity Log</h2>
            <div className="log-output">
              {logs.map((line, index) => (
                <p key={index} className={`log-line ${
                  line.includes('ERROR') || line.includes('MISS') ? 'log-error' :
                  line.includes('HIT') || line.includes('SUCCESS') ? 'log-success' :
                  line.includes('CHAOS') ? 'log-chaos' :
                  line.includes('RECOVERY') ? 'log-recovery' : ''
                }`}>
                  {line}
                </p>
              ))}
            </div>
          </div>
        </>
      )}

      {activeTab === 'logs' && (
        <div className="node-logs-grid">
          {NODE_NAMES.map(nodeName => (
            <div className="node-log-window" key={nodeName}>
              <div className="node-log-header">
                <span className={`status-light ${
                  clusterStatus && clusterStatus[nodeName] &&
                  clusterStatus[nodeName].status === 'UP' ? 'green' : 'red'
                }`}></span>
                <h3>{nodeName.toUpperCase()}</h3>
              </div>
              <div className="node-log-output">
                {nodeLogs[nodeName].length === 0 ? (
                  <p className="log-line log-empty">No activity yet...</p>
                ) : (
                  nodeLogs[nodeName].map((line, index) => (
                    <p key={index} className={`log-line ${
                      line.includes('PUT') ? 'log-success' :
                      line.includes('KILL') ? 'log-chaos' :
                      line.includes('RECOVERY') || line.includes('START') ? 'log-recovery' :
                      line.includes('ERROR') ? 'log-error' : ''
                    }`}>
                      {line}
                    </p>
                  ))
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default App