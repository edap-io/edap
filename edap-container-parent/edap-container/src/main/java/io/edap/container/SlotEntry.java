package io.edap.container;

/**
 * 不可变 POJO：每次 mutate 整个替换，ConcurrentHashMap.put 原子发布。
 *  JDK 8 兼容版（无 record）。仅作为 ConcurrentHashMap 的 value 用，
 *  故无需 equals/hashCode。
 */
public final class SlotEntry {
    private final AppContext previous;
    private final AppContext current;
    private final AppContext staging;

    public SlotEntry(AppContext previous, AppContext current, AppContext staging) {
        this.previous = previous;
        this.current  = current;
        this.staging  = staging;
    }

    public AppContext previous() { return previous; }
    public AppContext current()  { return current; }
    public AppContext staging()  { return staging; }

    public SlotEntry withSlot(Slot slot, AppContext ctx) {
        switch (slot) {
            case PREVIOUS: return new SlotEntry(ctx, current, staging);
            case CURRENT:  return new SlotEntry(previous, ctx, staging);
            case STAGING:  return new SlotEntry(previous, current, ctx);
            default: throw new IllegalArgumentException("unknown slot: " + slot);
        }
    }

    public AppContext slotOf(Slot slot) {
        switch (slot) {
            case PREVIOUS: return previous;
            case CURRENT:  return current;
            case STAGING:  return staging;
            default: throw new IllegalArgumentException("unknown slot: " + slot);
        }
    }

    public boolean isEmpty() {
        return previous == null && current == null && staging == null;
    }

    @Override
    public String toString() {
        return "SlotEntry{previous=" + previous
                + ", current=" + current
                + ", staging=" + staging + '}';
    }
}