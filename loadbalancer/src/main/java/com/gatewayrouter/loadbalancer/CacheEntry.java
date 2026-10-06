package com.gatewayrouter.loadbalancer;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "cache_records")
public class CacheEntry {

    @Id
    private String key;
    private String value;
    private long lastSavedTimestamp;
    private long expiryTime; 

    // Default constructor required by MongoDB for reflection
    public CacheEntry() {}

    public CacheEntry(String key, String value, long expiryTime) {
        this.key = key;
        this.value = value;
        this.lastSavedTimestamp = System.currentTimeMillis();
        this.expiryTime=expiryTime;
    }

    // Getters and Setters
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public long getExpiryTime()
    {
        return this.expiryTime;
    }
    public void setExpiryTime(long expiryTime)
    {
        this.expiryTime=expiryTime;
    }

    public long getLastSavedTimestamp() { return lastSavedTimestamp; }
    public void setLastSavedTimestamp(long lastSavedTimestamp) { this.lastSavedTimestamp = lastSavedTimestamp; }
}