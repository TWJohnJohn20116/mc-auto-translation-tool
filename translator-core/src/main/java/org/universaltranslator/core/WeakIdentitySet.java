package org.universaltranslator.core;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * A tiny, bounded set that answers "is this the same object I just handled?" without retaining it.
 *
 * <p>Render bridges need exactly that when one producer and one consumer are both hooked: the
 * inventory tooltip list produced by {@code Screen.getTooltipFromItem} is handed straight to
 * {@code DrawContext.drawTooltip} in the same frame, and translating it twice sends the already
 * translated text back to the provider. A mark has to travel with the list instance, must not
 * outlive it, and must be free of the cost of hashing the contents — comparing a tooltip list by
 * value would also be wrong, because two independently produced tooltips can be equal.
 *
 * <p>The implementation is a fixed ring of {@link WeakReference}s compared by identity, so a
 * caller never leaks a retained list even if it forgets to clear anything. Capacity only has to
 * cover the lists handled between two collections: a handful of tooltips per frame.
 */
public final class WeakIdentitySet {
    private static final int CAPACITY = 8;

    private final WeakReference<?>[] slots = new WeakReference<?>[CAPACITY];
    private int cursor;

    /** Adds {@code value} as handled, evicting the oldest slot when the ring is full. */
    public synchronized void add(Object value) {
        if (value == null) {
            return;
        }
        slots[cursor] = new WeakReference<Object>(value);
        cursor = (cursor + 1) % CAPACITY;
    }

    /**
     * True when the exact same instance was added and is still reachable.
     *
     * <p>An instance the collector already reclaimed cannot be passed in again, so a cleared slot
     * is simply a miss.
     */
    public synchronized boolean contains(Object value) {
        if (value == null) {
            return false;
        }
        for (WeakReference<?> slot : slots) {
            if (slot != null && slot.get() == value) {
                return true;
            }
        }
        return false;
    }

    /** Forgets every mark. Only useful in tests. */
    public synchronized void clear() {
        Arrays.fill(slots, null);
        cursor = 0;
    }
}
