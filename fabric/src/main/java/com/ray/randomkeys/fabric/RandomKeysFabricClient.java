package com.ray.randomkeys.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class RandomKeysFabricClient implements ClientModInitializer {
    private static final Set<String> enabledKeys = new LinkedHashSet<>();
    private static final Map<String, InputUtil.Key> originalKeys = new HashMap<>();
    private static final Map<String, InputUtil.Key> lockedLayout = new LinkedHashMap<>();
    private static final Random RANDOM = new Random();
    private static final int[] RANDOM_KEY_CODES = buildKeyboardPool();
    private static long lastLockWarningMs;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RandomKeysFabric.WHITELIST_S2C, (client, handler, buf, responseSender) -> {
            int count = Math.min(buf.readVarInt(), 4096);
            List<String> keys = new ArrayList<>(count);
            for (int i = 0; i < count; i++) keys.add(buf.readString(512));
            client.execute(() -> applyWhitelist(keys));
        });

        ClientPlayNetworking.registerGlobalReceiver(RandomKeysFabric.MUTATE_S2C, (client, handler, buf, responseSender) ->
                client.execute(RandomKeysFabricClient::mutateOne));

        ClientPlayNetworking.registerGlobalReceiver(RandomKeysFabric.APPLY_LAYOUT_S2C, (client, handler, buf, responseSender) -> {
            String donor = buf.readString(128);
            LinkedHashMap<String, String> map = RandomKeysFabric.readMap(buf);
            client.execute(() -> applyLayout(map, donor));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> restoreAll());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> restoreAll());
        ClientTickEvents.END_CLIENT_TICK.register(client -> enforceLockedBindings(true));
        HudRenderCallback.EVENT.register(RandomKeysFabricClient::renderHud);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("randomkeysclient")
                        .then(ClientCommandManager.literal("available")
                                .executes(ctx -> showAvailable(""))
                                .then(ClientCommandManager.argument("filter", StringArgumentType.greedyString())
                                        .executes(ctx -> showAvailable(StringArgumentType.getString(ctx, "filter")))))
        ));
    }

    private static int showAvailable(String filter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options == null) return 0;
        String needle = filter.toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (KeyBinding binding : client.options.allKeys) {
            String id = binding.getTranslationKey();
            if (needle.isEmpty() || id.toLowerCase(Locale.ROOT).contains(needle)) ids.add(id);
        }
        ids.sort(String::compareTo);
        client.player.sendMessage(Text.literal("Registered KeyMappings (" + ids.size() + "):"), false);
        for (String id : ids) client.player.sendMessage(Text.literal(" - " + id), false);
        return ids.size();
    }

    private static void applyWhitelist(List<String> newKeys) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null) return;
        releaseAllKeys();
        Set<String> incoming = new LinkedHashSet<>(newKeys);

        for (String old : new ArrayList<>(enabledKeys)) {
            if (!incoming.contains(old)) restoreOne(old);
        }
        enabledKeys.clear();
        enabledKeys.addAll(incoming);

        for (String id : enabledKeys) {
            KeyBinding binding = findBinding(id);
            if (binding == null) continue;
            InputUtil.Key current = boundKey(binding);
            originalKeys.putIfAbsent(id, current);
            lockedLayout.putIfAbsent(id, current);
        }
        enforceLockedBindings(false);
        sendSnapshot();
    }

    public static void mutateOne() {
        List<KeyBinding> candidates = new ArrayList<>();
        for (String id : enabledKeys) {
            KeyBinding binding = findBinding(id);
            if (binding != null) candidates.add(binding);
        }
        if (candidates.isEmpty()) return;

        KeyBinding selected = candidates.get(RANDOM.nextInt(candidates.size()));
        InputUtil.Key current = boundKey(selected);
        InputUtil.Key next;
        do {
            if (RANDOM.nextInt(RANDOM_KEY_CODES.length + 1) == RANDOM_KEY_CODES.length) {
                next = InputUtil.UNKNOWN_KEY;
            } else {
                int code = RANDOM_KEY_CODES[RANDOM.nextInt(RANDOM_KEY_CODES.length)];
                next = InputUtil.fromKeyCode(code, -1);
            }
        } while (next.equals(current));

        releaseAllKeys();
        selected.setBoundKey(next);
        lockedLayout.put(selected.getTranslationKey(), next);
        KeyBinding.updateKeysByCode();
        releaseAllKeys();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("按鍵改變：")
                    .append(Text.translatable(selected.getTranslationKey()))
                    .append(Text.literal(" → "))
                    .append(selected.getBoundKeyLocalizedText()), true);
        }
        sendSnapshot();
    }

    public static void applyLayout(Map<String, String> map, String donor) {
        releaseAllKeys();
        for (String id : enabledKeys) {
            String keyName = map.get(id);
            if (keyName == null) continue;
            KeyBinding binding = findBinding(id);
            if (binding == null) continue;
            try {
                InputUtil.Key key = InputUtil.fromTranslationKey(keyName);
                binding.setBoundKey(key);
                lockedLayout.put(id, key);
            } catch (IllegalArgumentException ignored) {
            }
        }
        KeyBinding.updateKeysByCode();
        releaseAllKeys();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal("已取得 " + donor + " 的亂鍵配置"), true);
        sendSnapshot();
    }

    private static void enforceLockedBindings(boolean warn) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null || enabledKeys.isEmpty()) return;
        boolean changed = false;
        for (String id : enabledKeys) {
            KeyBinding binding = findBinding(id);
            if (binding == null) continue;
            InputUtil.Key desired = lockedLayout.get(id);
            if (desired == null) {
                InputUtil.Key current = boundKey(binding);
                originalKeys.putIfAbsent(id, current);
                lockedLayout.put(id, current);
                continue;
            }
            if (!boundKey(binding).equals(desired)) {
                if (!changed) releaseAllKeys();
                binding.setBoundKey(desired);
                changed = true;
            }
        }
        if (changed) {
            KeyBinding.updateKeysByCode();
            releaseAllKeys();
            if (warn && client.player != null) {
                long now = System.currentTimeMillis();
                if (now - lastLockWarningMs >= 1500L) {
                    client.player.sendMessage(Text.literal("亂鍵中的按鍵已鎖定，不能在設定中手動變更"), true);
                    lastLockWarningMs = now;
                }
            }
        }
    }

    private static void sendSnapshot() {
        if (!ClientPlayNetworking.canSend(RandomKeysFabric.SNAPSHOT_C2S)) return;
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String id : enabledKeys) {
            KeyBinding binding = findBinding(id);
            if (binding != null) map.put(id, boundKey(binding).getTranslationKey());
        }
        PacketByteBuf buf = PacketByteBufs.create();
        RandomKeysFabric.writeMap(buf, map);
        ClientPlayNetworking.send(RandomKeysFabric.SNAPSHOT_C2S, buf);
    }

    private static void renderHud(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options == null || client.options.hudHidden) return;
        List<KeyBinding> rows = new ArrayList<>();
        for (String id : enabledKeys) {
            KeyBinding binding = findBinding(id);
            if (binding != null) rows.add(binding);
        }
        if (rows.isEmpty()) return;

        int padding = 5;
        int lineHeight = client.textRenderer.fontHeight + 2;
        int width = 0;
        List<Text> lines = new ArrayList<>();
        for (KeyBinding binding : rows) {
            Text line = Text.translatable(binding.getTranslationKey()).copy()
                    .append(Text.literal(": "))
                    .append(binding.getBoundKeyLocalizedText());
            lines.add(line);
            width = Math.max(width, client.textRenderer.getWidth(line));
        }
        int x = context.getScaledWindowWidth() - width - padding * 2 - 4;
        int y = 6;
        int height = lines.size() * lineHeight + padding * 2;
        context.fill(x - 2, y - 2, context.getScaledWindowWidth() - 4, y + height, 0x88000000);
        int ty = y + padding;
        for (Text line : lines) {
            context.drawTextWithShadow(client.textRenderer, line, x + padding, ty, 0xFFFFFF);
            ty += lineHeight;
        }
    }

    private static KeyBinding findBinding(String translationKey) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null) return null;
        for (KeyBinding binding : client.options.allKeys) {
            if (binding.getTranslationKey().equals(translationKey)) return binding;
        }
        return null;
    }

    private static InputUtil.Key boundKey(KeyBinding binding) {
        return InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
    }

    private static void restoreOne(String id) {
        KeyBinding binding = findBinding(id);
        InputUtil.Key original = originalKeys.remove(id);
        lockedLayout.remove(id);
        if (original != null && binding != null) binding.setBoundKey(original);
    }

    private static void restoreAll() {
        releaseAllKeys();
        for (String id : new ArrayList<>(originalKeys.keySet())) restoreOne(id);
        enabledKeys.clear();
        lockedLayout.clear();
        KeyBinding.updateKeysByCode();
        releaseAllKeys();
    }

    private static void releaseAllKeys() {
        KeyBinding.unpressAll();
    }

    private static int[] buildKeyboardPool() {
        List<Integer> keys = new ArrayList<>();
        for (int k = 32; k <= 96; k++) keys.add(k);
        keys.add(161); keys.add(162);
        for (int k = 257; k <= 269; k++) keys.add(k); // Escape (256) intentionally excluded.
        for (int k = 280; k <= 284; k++) keys.add(k);
        for (int k = 290; k <= 314; k++) keys.add(k);
        for (int k = 320; k <= 336; k++) keys.add(k);
        for (int k = 340; k <= 348; k++) keys.add(k);
        return keys.stream().mapToInt(Integer::intValue).toArray();
    }
}
