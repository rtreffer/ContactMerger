package de.measite.contactmerger.util;

import java.util.HashMap;
import java.util.LinkedHashMap;

import de.measite.contactmerger.contacts.ContactDataMapper;

/**
 * An LRU for method calls that expires the entries partially based on hashcode, thus avoiding
 * stampedes.
 */
public class ShiftedExpireLRU extends LinkedHashMap<ContactDataMapper.MethodCall, Object> {
    private final int capacity;
    private long ttl;
    private HashMap<ContactDataMapper.MethodCall, ContactDataMapper.MethodCall> keyNormalization;

    public ShiftedExpireLRU(long ttl, int capacity) {
        super(capacity, 0.75f, true);
        this.ttl = ttl;
        this.capacity = capacity;
        keyNormalization = new LRU<>(capacity * 2);
    }

    @Override
    public synchronized Object put(ContactDataMapper.MethodCall key, Object value) {
        keyNormalization.put(key, key);
        return super.put(key, value);
    }

    @Override
    public synchronized Object get(Object key) {
        Object value = super.get(key);
        if (value == null) return null;
        if (!(key instanceof ContactDataMapper.MethodCall)) return null;
        ContactDataMapper.MethodCall ikey = (ContactDataMapper.MethodCall) key;
        ContactDataMapper.MethodCall k = keyNormalization.get(key);
        if (k == null) {
            keyNormalization.put(ikey, ikey);
            k = ikey;
        }
        if ((k.created + ttl) * ((1 + (k.hashCode() & 0xffff)) / 65535f * 0.25 + 0.75) > System.currentTimeMillis())
            return null;
        return value;
    }

    @Override
    protected boolean removeEldestEntry(Entry<ContactDataMapper.MethodCall, Object> eldest) {
        return size() > capacity;
    }
}
