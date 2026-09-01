package com.ray.randomkeys.fabric;

import net.fabricmc.api.ModInitializer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runtime migration for v0.3.0's overly broad GLFW numeric-range pool.
 *
 * <p>The original pool included unnamed numeric gaps and uncommon GLFW tokens such as
 * WORLD_1/WORLD_2, F13-F25 and Super/Windows. This initializer runs immediately after the
 * main server initializer, replaces every pool slot with a known common-PC key, sanitizes
 * already-persisted bad KEYSYM snapshots, and writes the migrated server state back out.</p>
 */
public final class CommonKeyPoolPatch implements ModInitializer {
    private static final int[] SAFE_KEYS = {
            32,
            39, 44, 45, 46, 47,
            48, 49, 50, 51, 52, 53, 54, 55, 56, 57,
            59, 61,
            65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77,
            78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
            91, 92, 93, 96,

            257, 258, 259,
            260, 261,
            262, 263, 264, 265,
            266, 267, 268, 269,

            290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301,

            320, 321, 322, 323, 324, 325, 326, 327, 328, 329,
            330, 331, 332, 333, 334, 335,

            340, 341, 342,
            344, 345, 346
    };

    @Override
    public void onInitialize() {
        try {
            patchRandomPool();
            sanitizeSavedLayouts();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to enforce the common keyboard pool", e);
        }
    }

    private static void patchRandomPool() throws ReflectiveOperationException {
        Field field = RandomKeysFabric.class.getDeclaredField("RANDOM_KEY_CODES");
        field.setAccessible(true);
        int[] pool = (int[]) field.get(null);
        for (int i = 0; i < pool.length; i++) {
            pool[i] = SAFE_KEYS[i % SAFE_KEYS.length];
        }
    }

    @SuppressWarnings("unchecked")
    private static void sanitizeSavedLayouts() throws ReflectiveOperationException {
        Field snapshotsField = RandomKeysFabric.class.getDeclaredField("snapshots");
        snapshotsField.setAccessible(true);
        Map<UUID, LinkedHashMap<String, String>> snapshots =
                (Map<UUID, LinkedHashMap<String, String>>) snapshotsField.get(null);

        boolean changed = false;
        for (LinkedHashMap<String, String> layout : snapshots.values()) {
            for (Map.Entry<String, String> entry : layout.entrySet()) {
                String sanitized = sanitizeToken(entry.getValue());
                if (!sanitized.equals(entry.getValue())) {
                    entry.setValue(sanitized);
                    changed = true;
                }
            }
        }

        if (changed) {
            Method saveConfig = RandomKeysFabric.class.getDeclaredMethod("saveConfig");
            saveConfig.setAccessible(true);
            saveConfig.invoke(null);
        }
    }

    private static String sanitizeToken(String token) {
        if (token == null || token.isBlank()) return randomSafeToken();
        if ("unbound".equals(token) || !token.startsWith("KEYSYM:")) return token;

        try {
            int code = Integer.parseInt(token.substring("KEYSYM:".length()));
            if (isSafe(code)) return token;
        } catch (NumberFormatException ignored) {
        }
        return randomSafeToken();
    }

    private static String randomSafeToken() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextInt(SAFE_KEYS.length + 1) == SAFE_KEYS.length) return "unbound";
        return "KEYSYM:" + SAFE_KEYS[random.nextInt(SAFE_KEYS.length)];
    }

    private static boolean isSafe(int code) {
        for (int safe : SAFE_KEYS) if (safe == code) return true;
        return false;
    }
}
