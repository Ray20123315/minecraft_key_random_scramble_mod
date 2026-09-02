package com.ray.randomkeys.fabric;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-save authoritative state for Random Keys Survival.
 *
 * <p>The state is always obtained from the server Overworld so every dimension in one save
 * shares the same whitelist, player layouts, and multiplayer-exchange countdown.</p>
 */
public final class RandomKeysWorldState extends PersistentState {
    private static final String STORAGE_ID = RandomKeysFabric.MOD_ID;
    private static final int DATA_VERSION = 1;
    private static final String KEY_DATA_VERSION = "DataVersion";
    private static final String KEY_ENABLED_KEYS = "EnabledKeys";
    private static final String KEY_LAYOUTS = "Layouts";
    private static final String KEY_TICKS_UNTIL_SWAP = "TicksUntilSwap";

    private final LinkedHashSet<String> enabledKeys = new LinkedHashSet<>();
    private final LinkedHashMap<UUID, LinkedHashMap<String, String>> layouts = new LinkedHashMap<>();
    private int ticksUntilSwap = RandomKeysFabric.SWAP_INTERVAL_TICKS;

    public RandomKeysWorldState() {
        enabledKeys.addAll(RandomKeysFabric.DEFAULT_KEYS);
    }

    public static RandomKeysWorldState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(
                RandomKeysWorldState::fromNbt,
                RandomKeysWorldState::new,
                STORAGE_ID
        );
    }

    public static RandomKeysWorldState fromNbt(NbtCompound nbt) {
        RandomKeysWorldState state = new RandomKeysWorldState();
        boolean sanitized = false;

        if (nbt.contains(KEY_ENABLED_KEYS, NbtElement.LIST_TYPE)) {
            state.enabledKeys.clear();
            NbtList list = nbt.getList(KEY_ENABLED_KEYS, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) {
                String key = list.getString(i);
                if (isValidTranslationKey(key)) {
                    state.enabledKeys.add(key);
                } else {
                    sanitized = true;
                }
            }
        } else {
            sanitized = true;
        }

        if (nbt.contains(KEY_LAYOUTS, NbtElement.COMPOUND_TYPE)) {
            NbtCompound layoutsNbt = nbt.getCompound(KEY_LAYOUTS);
            for (String uuidText : layoutsNbt.getKeys()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidText);
                } catch (IllegalArgumentException ignored) {
                    sanitized = true;
                    continue;
                }
                if (!layoutsNbt.contains(uuidText, NbtElement.COMPOUND_TYPE)) {
                    sanitized = true;
                    continue;
                }

                NbtCompound layoutNbt = layoutsNbt.getCompound(uuidText);
                LinkedHashMap<String, String> layout = new LinkedHashMap<>();
                for (String translationKey : layoutNbt.getKeys()) {
                    if (!isValidTranslationKey(translationKey)
                            || !state.enabledKeys.contains(translationKey)
                            || !layoutNbt.contains(translationKey, NbtElement.STRING_TYPE)) {
                        sanitized = true;
                        continue;
                    }
                    String token = layoutNbt.getString(translationKey);
                    if (!isValidBindingToken(token)) {
                        sanitized = true;
                        continue;
                    }
                    layout.put(translationKey, token);
                }
                if (!layout.isEmpty()) state.layouts.put(uuid, layout);
            }
        }

        if (nbt.contains(KEY_TICKS_UNTIL_SWAP, NbtElement.INT_TYPE)) {
            int value = nbt.getInt(KEY_TICKS_UNTIL_SWAP);
            if (value >= 0 && value <= RandomKeysFabric.SWAP_INTERVAL_TICKS) {
                state.ticksUntilSwap = value;
            } else {
                state.ticksUntilSwap = RandomKeysFabric.SWAP_INTERVAL_TICKS;
                sanitized = true;
            }
        } else {
            state.ticksUntilSwap = RandomKeysFabric.SWAP_INTERVAL_TICKS;
            sanitized = true;
        }

        if (!nbt.contains(KEY_DATA_VERSION, NbtElement.INT_TYPE)
                || nbt.getInt(KEY_DATA_VERSION) != DATA_VERSION) {
            sanitized = true;
        }

        if (sanitized) state.markDirty();
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt(KEY_DATA_VERSION, DATA_VERSION);
        nbt.putInt(KEY_TICKS_UNTIL_SWAP, ticksUntilSwap);

        NbtList enabledList = new NbtList();
        for (String key : enabledKeys) enabledList.add(NbtString.of(key));
        nbt.put(KEY_ENABLED_KEYS, enabledList);

        NbtCompound layoutsNbt = new NbtCompound();
        for (Map.Entry<UUID, LinkedHashMap<String, String>> playerEntry : layouts.entrySet()) {
            NbtCompound layoutNbt = new NbtCompound();
            for (String key : enabledKeys) {
                String token = playerEntry.getValue().get(key);
                if (token != null && isValidBindingToken(token)) layoutNbt.putString(key, token);
            }
            if (!layoutNbt.isEmpty()) layoutsNbt.put(playerEntry.getKey().toString(), layoutNbt);
        }
        nbt.put(KEY_LAYOUTS, layoutsNbt);
        return nbt;
    }

    public List<String> enabledKeys() {
        return new ArrayList<>(enabledKeys);
    }

    public LinkedHashMap<String, String> layout(UUID uuid) {
        LinkedHashMap<String, String> layout = layouts.get(uuid);
        return layout == null ? new LinkedHashMap<>() : new LinkedHashMap<>(layout);
    }

    public boolean initializeMissingBindings(UUID uuid, Map<String, String> incoming) {
        if (incoming == null || incoming.isEmpty()) return false;
        LinkedHashMap<String, String> current = layouts.get(uuid);
        LinkedHashMap<String, String> updated = current == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(current);
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
            markDirty();
        }
        return changed;
    }

    public boolean setBinding(UUID uuid, String translationKey, String token) {
        if (!enabledKeys.contains(translationKey) || !isValidBindingToken(token)) return false;
        LinkedHashMap<String, String> layout = layouts.get(uuid);
        if (layout == null || !layout.containsKey(translationKey)) return false;
        if (token.equals(layout.get(translationKey))) return false;
        layout.put(translationKey, token);
        markDirty();
        return true;
    }

    public boolean addEnabledKey(String translationKey) {
        if (!isValidTranslationKey(translationKey) || !enabledKeys.add(translationKey)) return false;
        markDirty();
        return true;
    }

    public boolean removeEnabledKey(String translationKey) {
        if (!enabledKeys.remove(translationKey)) return false;
        for (LinkedHashMap<String, String> layout : layouts.values()) layout.remove(translationKey);
        layouts.values().removeIf(Map::isEmpty);
        markDirty();
        return true;
    }

    public void resetEnabledKeys() {
        Set<String> defaults = new LinkedHashSet<>(RandomKeysFabric.DEFAULT_KEYS);
        boolean changed = !enabledKeys.equals(defaults);
        enabledKeys.clear();
        enabledKeys.addAll(defaults);
        for (LinkedHashMap<String, String> layout : layouts.values()) {
            if (layout.keySet().removeIf(key -> !enabledKeys.contains(key))) changed = true;
        }
        layouts.values().removeIf(Map::isEmpty);
        if (changed) markDirty();
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
        if (!normalized.keySet().containsAll(enabledKeys)) {
            throw new IllegalArgumentException("Replacement layout is incomplete");
        }
        layouts.put(uuid, normalized);
        markDirty();
    }

    public int tickSwapCountdown() {
        if (ticksUntilSwap > 0) {
            ticksUntilSwap--;
            markDirty();
        }
        return ticksUntilSwap;
    }

    public int ticksUntilSwap() {
        return ticksUntilSwap;
    }

    public void resetSwapCountdown() {
        if (ticksUntilSwap != RandomKeysFabric.SWAP_INTERVAL_TICKS) {
            ticksUntilSwap = RandomKeysFabric.SWAP_INTERVAL_TICKS;
            markDirty();
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
