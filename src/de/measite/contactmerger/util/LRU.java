package de.measite.contactmerger.util;

import java.util.LinkedHashMap;

/**
 * Classic LRU implementation.
 */
public class LRU<K, V> extends LinkedHashMap<K, V> {

    private final int capacity;

    public LRU(int capacity) {
        super(capacity, 0.75f, true);
        this.capacity = capacity;
    }
    @Override
    protected boolean removeEldestEntry(Entry<K, V> eldest) {
        return size() > capacity;
    }
}
