package cn.xiuxius.mc.largerinventory.database.cache;

import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * 单个玩家的分页 LRU 缓存。
 *
 * <h3>线程模型</h3>
 * 所有对该对象的操作（get / put / update / collectDirty / markClean / clear）
 * 均须在主线程调用。异步写入完成后通过 runTask 回调主线程再调用 markClean。
 *
 * <h3>脏页版本机制</h3>
 * 每次 update() 递增 version。collectDirty() 捕获当前 version 并记入快照。
 * 异步写入完成后调用 markClean(page, capturedVersion)，仅当 version 未变才清除
 * dirty 标志，从而避免"写入快照时又有新修改"导致 dirty 被错误清除的数据丢失问题。
 */
public class PageCache {

    /** 驱逐脏页时的回调（主线程调用） */
    @FunctionalInterface
    public interface EvictionListener {
        void onDirtyEvict(int page, Map<Integer, ItemStack> snapshot);
    }

    /** 快照条目，用于异步写入任务 */
    public record DirtySnapshot(int page, Map<Integer, ItemStack> items, int version) {}

    private static final class Entry {
        final Map<Integer, ItemStack> items = new HashMap<>();
        boolean dirty;
        int version;

        Entry(Map<Integer, ItemStack> source, boolean dirty) {
            this.items.putAll(source);
            this.dirty = dirty;
            this.version = dirty ? 1 : 0;
        }

        void update(Map<Integer, ItemStack> newItems) {
            items.clear();
            items.putAll(newItems);
            dirty = true;
            version++;
        }
    }

    private final LinkedHashMap<Integer, Entry> lru;

    public PageCache(int capacity, EvictionListener evictionListener) {
        lru = new LinkedHashMap<>(capacity + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Entry> eldest) {
                if (size() > capacity) {
                    Entry e = eldest.getValue();
                    if (e.dirty) {
                        Map<Integer, ItemStack> snapshot = Collections.unmodifiableMap(new HashMap<>(e.items));
                        evictionListener.onDirtyEvict(eldest.getKey(), snapshot);
                    }
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * 读取页面物品（更新 LRU 访问顺序）。返回缓存内部的 live map，
     * 调用方仅读取，勿直接写入（写入请用 update）。
     */
    public Map<Integer, ItemStack> get(int page) {
        Entry e = lru.get(page);
        return e != null ? e.items : null;
    }

    /** 放入新页（从 DB 加载后），标记为干净 */
    public void put(int page, Map<Integer, ItemStack> items) {
        lru.put(page, new Entry(items, false));
    }

    /**
     * 更新页面内容并标记为脏（玩家翻页快照时调用）。
     * 若已存在则复用条目（避免触发 LRU 驱逐），否则创建新条目。
     */
    public void update(int page, Map<Integer, ItemStack> items) {
        Entry e = lru.get(page);
        if (e != null) {
            e.update(items);
        } else {
            lru.put(page, new Entry(items, true));
        }
    }

    /**
     * 收集所有脏页快照（不影响 LRU 访问顺序，通过 entrySet 迭代）。
     * 返回的 items 是不可变快照，可安全传递给异步线程。
     */
    public List<DirtySnapshot> collectDirty() {
        List<DirtySnapshot> result = new ArrayList<>();
        for (Map.Entry<Integer, Entry> mapEntry : lru.entrySet()) {
            Entry e = mapEntry.getValue();
            if (e.dirty) {
                Map<Integer, ItemStack> snapshot = Collections.unmodifiableMap(new HashMap<>(e.items));
                result.add(new DirtySnapshot(mapEntry.getKey(), snapshot, e.version));
            }
        }
        return result;
    }

    /**
     * 直接标记某页为脏（用于就地修改缓存内容后、无需重新 put 时）。
     * 要求调用方已通过 get() 获取了 live map 并直接修改。
     */
    public void markDirty(int page) {
        Entry e = lru.get(page);
        if (e != null) {
            e.dirty = true;
            e.version++;
        }
    }

    /**
     * 异步写入成功后在主线程调用。
     * 仅当 version 未变（写入后无新修改）时才清除 dirty，防止数据丢失。
     */
    public void markClean(int page, int version) {
        Entry e = lru.get(page);
        if (e != null && e.version == version) {
            e.dirty = false;
        }
    }

    /** 清空缓存（玩家退出 / 死亡清档时调用） */
    public void clear() {
        lru.clear();
    }

    public int size() {
        return lru.size();
    }
}
