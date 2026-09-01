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
import java.util.Set;

public final class RandomKeysFabricClient implements ClientModInitializer {
    private static final Set<String> enabledKeys = new LinkedHashSet<>();
    private static final Map<String, InputUtil.Key> originalKeys = new HashMap<>();
    private static final Map<String, InputUtil.Key> lockedLayout = new LinkedHashMap<>();
    private static long lastLockWarningMs;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RandomKeysFabric.SYNC_S2C, (client, handler, buf, responseSender) -> {
            int count = Math.min(buf.readVarInt(), 4096);
            List<String> keys = new ArrayList<>(count);
            for (int i = 0; i < count; i++) keys.add(buf.readString(512));
            LinkedHashMap<String, String> savedLayout = RandomKeysFabric.readMap(buf);
            client.execute(() -> applySync(keys, savedLayout));
        });

        ClientPlayNetworking.registerGlobalReceiver(RandomKeysFabric.MUTATE_S2C, (client, handler, buf, responseSender) -> {
            String id = buf.readString(512);
            String keyToken = buf.readString(512);
            client.execute(() -> applyMutation(id, keyToken));
        });

        ClientPlayNetworking.registerGlobalReceiver(RandomKeysFabric.APPLY_LAYOUT_S2C, (client, handler, buf, responseSender) -> {
            String donor = buf.readString(128);
            LinkedHashMap<String, String> map = RandomKeysFabric.readMap(buf);
            client.execute(() -> applyLayout(map, donor, true));
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

    private static void applySync(List<String> newKeys, Map<String, String> serverLayout) {
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

        applyLayout(serverLayout, null, false);
        enforceLockedBindings(false);
        sendSnapshot();
    }

    private static void applyMutation(String id, String keyToken) {
        if (!enabledKeys.contains(id)) return;
        KeyBinding binding = findBinding(id);
        if (binding == null) return;
        InputUtil.Key next = decodeKey(keyToken);
        if (next == null) return;

        releaseAllKeys();
        binding.setBoundKey(next);
        lockedLayout.put(id, next);
        KeyBinding.updateKeysByCode();
        releaseAllKeys();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("按鍵改變：")
                    .append(Text.translatable(binding.getTranslationKey()))
                    .append(Text.literal(" → "))
                    .append(binding.getBoundKeyLocalizedText()), true);
        }
        sendSnapshot();
    }

    public static void applyLayout(Map<String, String> map, String donor, boolean announce) {
        releaseAllKeys();
        for (String id : enabledKeys) {
            String token = map.get(id);
            if (token == null) continue;
            KeyBinding binding = findBinding(id);
            if (binding == null) continue;
            InputUtil.Key key = decodeKey(token);
            if (key == null) continue;
            binding.setBoundKey(key);
            lockedLayout.put(id, key);
        }
        KeyBinding.updateKeysByCode();
        releaseAllKeys();
        MinecraftClient client = MinecraftClient.getInstance();
        if (announce && donor != null && client.player != null) {
            client.player.sendMessage(Text.literal("已取得 " + donor + " 的亂鍵配置"), true);
        }
        if (announce) sendSnapshot();
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
            if (binding != null) map.put(id, encodeKey(boundKey(binding)));
        }
        PacketByteBuf buf = PacketByteBufs.create();
        RandomKeysFabric.writeMap(buf, map);
        ClientPlayNetworking.send(RandomKeysFabric.SNAPSHOT_C2S, buf);
    }

    private static String encodeKey(InputUtil.Key key) {
        if (key.equals(InputUtil.UNKNOWN_KEY)) return "unbound";
        return key.getCategory().name() + ":" + key.getCode();
    }

    private static InputUtil.Key decodeKey(String token) {
        if (token == null) return null;
        if (token.equals("unbound")) return InputUtil.UNKNOWN_KEY;
        int colon = token.indexOf(':');
        if (colon > 0 && colon < token.length() - 1) {
            try {
                InputUtil.Type type = InputUtil.Type.valueOf(token.substring(0, colon));
                int code = Integer.parseInt(token.substring(colon + 1));
                return type.createFromCode(code);
            } catch (IllegalArgumentException ignored) {
            }
        }
        try {
            return InputUtil.fromTranslationKey(token);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    private static void releaseAllKeys() {
        KeyBinding.unpressAll();
    }
}
