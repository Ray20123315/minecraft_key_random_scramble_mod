package com.ray.randomkeys.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RandomKeysWorldData extends SavedData {
    private static final String STORAGE_ID = RandomKeysForge.MOD_ID;
    private static final int DATA_VERSION = 1;
    private static final String KEY_DATA_VERSION = "DataVersion";
    private static final String KEY_ENABLED_KEYS = "EnabledKeys";
    private static final String KEY_LAYOUTS = "Layouts";
    private static final String KEY_TICKS_UNTIL_SWAP = "TicksUntilSwap";

    private final LinkedHashSet<String> enabledKeys = new LinkedHashSet<>();
    private final LinkedHashMap<UUID, LinkedHashMap<String, String>> layouts = new LinkedHashMap<>();
    private int ticksUntilSwap = RandomKeysForge.SWAP_INTERVAL_TICKS;

    public RandomKeysWorldData() {
        enabledKeys.addAll(RandomKeysForge.DEFAULT_KEYS);
    }

    public static RandomKeysWorldData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                RandomKeysWorldData::load,
                RandomKeysWorldData::new,
                STORAGE_ID
        );
    }

    public static RandomKeysWorldData load(CompoundTag tag) {
        RandomKeysWorldData data = new RandomKeysWorldData();
        boolean sanitized = false;

        if (tag.contains(KEY_ENABLED_KEYS, Tag.TAG_LIST)) {
            data.enabledKeys.clear();
            ListTag list = tag.getList(KEY_ENABLED_KEYS, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String key = list.getString(i);
                if (isValidTranslationKey(key)) data.enabledKeys.add(key);
                else sanitized = true;
            }
        } else {
            sanitized = true;
        }

        if (tag.contains(KEY_LAYOUTS, Tag.TAG_COMPOUND)) {
            CompoundTag layoutsTag = tag.getCompound(KEY_LAYOUTS);
            for (String uuidText : layoutsTag.getAllKeys()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidText);
                } catch (IllegalArgumentException ignored) {
                    sanitized = true;
                    continue;
                }
                if (!layoutsTag.contains(uuidText, Tag.TAG_COMPOUND)) {
                    sanitized = true;
                    continue;
                }
                CompoundTag layoutTag = layoutsTag.getCompound(uuidText);
                LinkedHashMap<String, String> layout = new LinkedHashMap<>();
                for (String translationKey : layoutTag.getAllKeys()) {
                    if (!isValidTranslationKey(translationKey)
                            || !data.enabledKeys.contains(translationKey)
                            || !layoutTag.contains(translationKey, Tag.TAG_STRING)) {
                        sanitized = true;
                        continue;
                    }
                    String token = layoutTag.getString(translationKey);
                    if (!isValidBindingToken(token)) {
                        sanitized = true;
                        continue;
                    }
                    layout.put(translationKey, token);
                }
                if (!layout.isEmpty()) data.layouts.put(uuid, layout);
            }
        }

        if (tag.contains(KEY_TICKS_UNTIL_SWAP, Tag.TAG_INT)) {
            int value = tag.getInt(KEY_TICKS_UNTIL_SWAP);
            if (value >= 0 && value <= RandomKeysForge.SWAP_INTERVAL_TICKS) data.ticksUntilSwap = value;
            else {
                data.ticksUntilSwap = RandomKeysForge.SWAP_INTERVAL_TICKS;
                sanitized = true;
            }
        } else {
            data.ticksUntilSwap = RandomKeysForge.SWAP_INTERVAL_TICKS;
            sanitized = true;
        }

        if (!tag.contains(KEY_DATA_VERSION, Tag.TAG_INT) || tag.getInt(KEY_DATA_VERSION) != DATA_VERSION) sanitized = true;
        if (sanitized) data.setDirty();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(KEY_DATA_VERSION, DATA_VERSION);
        tag.putInt(KEY_TICKS_UNTIL_SWAP, ticksUntilSwap);
        ListTag enabledList = new ListTag();
        for (String key : enabledKeys) enabledList.add(StringTag.valueOf(key));
        tag.put(KEY_ENABLED_KEYS, enabledList);
        CompoundTag layoutsTag = new CompoundTag();
        for (Map.Entry<UUID, LinkedHashMap<String, String>> playerEntry : layouts.entrySet()) {
            CompoundTag layoutTag = new CompoundTag();
            for (String key : enabledKeys) {
                String token = playerEntry.getValue().get(key);
                if (token != null && isValidBindingToken(token)) layoutTag.putString(key, token);
            }
            if (!layoutTag.isEmpty()) layoutsTag.put(playerEntry.getKey().toString(), layoutTag);
        }
        tag.put(KEY_LAYOUTS, layoutsTag);
        return tag;
    }

    public List<String> enabledKeys() { return new ArrayList<>(enabledKeys); }

    public LinkedHashMap<String, String> layout(UUID uuid) {
        LinkedHashMap<String, String> layout = layouts.get(uuid);
        return layout == null ? new LinkedHashMap<>() : new LinkedHashMap<>(layout);
    }

    public boolean initializeMissingBindings(UUID uuid, Map<String, String> incoming) {
        if (incoming == null || incoming.isEmpty()) return false;
        LinkedHashMap<String, String> current = layouts.get(uuid);
        LinkedHashMap<String, String> updated = current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
        boolean changed = false;
        for (String key : enabledKeys) {
            if (updated.containsKey(key)) continue;
            String token = incoming.get(key);
            if (!isValidBindingToken(token)) continue;
            updated.put(key, token);
            changed = true;
        }
        if (changed) {
            layouts.put(uuid, updated);
            setDirty();
        }
        return changed;
    }

    public boolean setBinding(UUID uuid, String translationKey, String token) {
        if (!enabledKeys.contains(translationKey) || !isValidBindingToken(token)) return false;
        LinkedHashMap<String, String> layout = layouts.get(uuid);
        if (layout == null || !layout.containsKey(translationKey)) return false;
        if (token.equals(layout.get(translationKey))) return false;
        layout.put(translationKey, token);
        setDirty();
        return true;
    }

    public boolean addEnabledKey(String translationKey) {
        if (!isValidTranslationKey(translationKey) || !enabledKeys.add(translationKey)) return false;
        setDirty();
        return true;
    }

    public boolean removeEnabledKey(String translationKey) {
        if (!enabledKeys.remove(translationKey)) return false;
        for (LinkedHashMap<String, String> layout : layouts.values()) layout.remove(translationKey);
        layouts.values().removeIf(Map::isEmpty);
        setDirty();
        return true;
    }

    public void resetEnabledKeys() {
        Set<String> defaults = new LinkedHashSet<>(RandomKeysForge.DEFAULT_KEYS);
        boolean changed = !enabledKeys.equals(defaults);
        enabledKeys.clear();
        enabledKeys.addAll(defaults);
        for (LinkedHashMap<String, String> layout : layouts.values()) {
            if (layout.keySet().removeIf(key -> !enabledKeys.contains(key))) changed = true;
        }
        layouts.values().removeIf(Map::isEmpty);
        if (changed) setDirty();
    }

    public boolean isLayoutComplete(UUID uuid) {
        LinkedHashMap<String, String> layout = layouts.get(uuid);
        return layout != null && layout.keySet().containsAll(enabledKeys);
    }

    public void replaceLayout(UUID uuid, Map<String, String> replacement) {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String key : enabledKeys) {
            String token = replacement.get(key);
            if (isValidBindingToken(token)) normalized.put(key, token);
        }
        if (!normalized.keySet().containsAll(enabledKeys)) throw new IllegalArgumentException("Replacement layout is incomplete");
        layouts.put(uuid, normalized);
        setDirty();
    }

    public int tickSwapCountdown() {
        if (ticksUntilSwap > 0) {
            ticksUntilSwap--;
            setDirty();
        }
        return ticksUntilSwap;
    }

    public int ticksUntilSwap() { return ticksUntilSwap; }

    public void resetSwapCountdown() {
        if (ticksUntilSwap != RandomKeysForge.SWAP_INTERVAL_TICKS) {
            ticksUntilSwap = RandomKeysForge.SWAP_INTERVAL_TICKS;
            setDirty();
        }
    }

    static boolean isValidTranslationKey(String key) {
        return key != null && !key.isBlank() && key.length() <= 512;
    }

    static boolean isValidBindingToken(String token) {
        if (token == null || token.isBlank() || token.length() > 64) return false;
        if ("unbound".equals(token)) return true;
        int colon = token.indexOf(':');
        if (colon <= 0 || colon == token.length() - 1) return false;
        String type = token.substring(0, colon);
        if (!"KEYSYM".equals(type) && !"SCANCODE".equals(type) && !"MOUSE".equals(type)) return false;
        try {
            int code = Integer.parseInt(token.substring(colon + 1));
            if (code < 0) return false;
            return "MOUSE".equals(type) ? code <= 255 : code <= 65535;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
