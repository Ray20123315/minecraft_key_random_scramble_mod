package com.ray.randomkeys.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Mod.EventBusSubscriber(modid = RandomKeysForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RandomKeysForgeClient {
    private static final Set<String> enabledKeys = new LinkedHashSet<>();
    private static final Map<String, InputConstants.Key> originalKeys = new HashMap<>();
    private static final Map<String, InputConstants.Key> lockedLayout = new LinkedHashMap<>();
    private static final Random RANDOM = new Random();
    private static final int[] RANDOM_KEY_CODES = buildKeyboardPool();
    private static long lastLockWarningMs;
    private static boolean wasInWorld;

    static {
        MinecraftForge.EVENT_BUS.register(new RuntimeEvents());
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("key_hud", (gui, graphics, partialTick, screenWidth, screenHeight) -> renderHud(graphics, screenWidth));
    }

    public static void applyWhitelist(List<String> newKeys) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) return;
        releaseAllKeys();
        Set<String> incoming = new LinkedHashSet<>(newKeys);
        for (String old : new ArrayList<>(enabledKeys)) if (!incoming.contains(old)) restoreOne(old);
        enabledKeys.clear();
        enabledKeys.addAll(incoming);
        for (String id : enabledKeys) {
            KeyMapping binding = findBinding(id);
            if (binding == null) continue;
            originalKeys.putIfAbsent(id, binding.getKey());
            lockedLayout.putIfAbsent(id, binding.getKey());
        }
        enforceLockedBindings(false);
        sendSnapshot();
    }

    public static void mutateOne() {
        List<KeyMapping> candidates = new ArrayList<>();
        for (String id : enabledKeys) {
            KeyMapping binding = findBinding(id);
            if (binding != null) candidates.add(binding);
        }
        if (candidates.isEmpty()) return;

        KeyMapping selected = candidates.get(RANDOM.nextInt(candidates.size()));
        InputConstants.Key current = selected.getKey();
        InputConstants.Key next;
        do {
            if (RANDOM.nextInt(RANDOM_KEY_CODES.length + 1) == RANDOM_KEY_CODES.length) {
                next = InputConstants.UNKNOWN;
            } else {
                int code = RANDOM_KEY_CODES[RANDOM.nextInt(RANDOM_KEY_CODES.length)];
                next = InputConstants.Type.KEYSYM.getOrCreate(code);
            }
        } while (next.equals(current));

        releaseAllKeys();
        selected.setKey(next);
        lockedLayout.put(selected.getName(), next);
        KeyMapping.resetMapping();
        releaseAllKeys();

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.displayClientMessage(Component.literal("按鍵改變：")
                .append(Component.translatable(selected.getName()))
                .append(Component.literal(" → "))
                .append(selected.getTranslatedKeyMessage()), true);
        sendSnapshot();
    }

    public static void applyLayout(Map<String, String> map, String donor) {
        releaseAllKeys();
        for (String id : enabledKeys) {
            String keyName = map.get(id);
            if (keyName == null) continue;
            KeyMapping binding = findBinding(id);
            if (binding == null) continue;
            try {
                InputConstants.Key key = InputConstants.getKey(keyName);
                binding.setKey(key);
                lockedLayout.put(id, key);
            } catch (IllegalArgumentException ignored) {
            }
        }
        KeyMapping.resetMapping();
        releaseAllKeys();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.displayClientMessage(Component.literal("已取得 " + donor + " 的亂鍵配置"), true);
        sendSnapshot();
    }

    private static void enforceLockedBindings(boolean warn) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null || enabledKeys.isEmpty()) return;
        boolean changed = false;
        for (String id : enabledKeys) {
            KeyMapping binding = findBinding(id);
            if (binding == null) continue;
            InputConstants.Key desired = lockedLayout.get(id);
            if (desired == null) {
                originalKeys.putIfAbsent(id, binding.getKey());
                lockedLayout.put(id, binding.getKey());
                continue;
            }
            if (!binding.getKey().equals(desired)) {
                if (!changed) releaseAllKeys();
                binding.setKey(desired);
                changed = true;
            }
        }
        if (changed) {
            KeyMapping.resetMapping();
            releaseAllKeys();
            if (warn && client.player != null) {
                long now = System.currentTimeMillis();
                if (now - lastLockWarningMs >= 1500L) {
                    client.player.displayClientMessage(Component.literal("亂鍵中的按鍵已鎖定，不能在設定中手動變更"), true);
                    lastLockWarningMs = now;
                }
            }
        }
    }

    private static void sendSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) return;
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String id : enabledKeys) {
            KeyMapping binding = findBinding(id);
            if (binding != null) map.put(id, binding.getKey().getName());
        }
        RandomKeysForge.CHANNEL.sendToServer(new RandomKeysForge.SnapshotPacket(map));
    }

    private static void renderHud(GuiGraphics graphics, int screenWidth) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) return;
        List<Component> lines = new ArrayList<>();
        int width = 0;
        for (String id : enabledKeys) {
            KeyMapping binding = findBinding(id);
            if (binding == null) continue;
            Component line = Component.translatable(binding.getName()).copy()
                    .append(Component.literal(": "))
                    .append(binding.getTranslatedKeyMessage());
            lines.add(line);
            width = Math.max(width, client.font.width(line));
        }
        if (lines.isEmpty()) return;
        int padding = 5;
        int lineHeight = client.font.lineHeight + 2;
        int x = screenWidth - width - padding * 2 - 4;
        int y = 6;
        graphics.fill(x - 2, y - 2, screenWidth - 4, y + lines.size() * lineHeight + padding * 2, 0x88000000);
        int ty = y + padding;
        for (Component line : lines) {
            graphics.drawString(client.font, line, x + padding, ty, 0xFFFFFF, true);
            ty += lineHeight;
        }
    }

    private static KeyMapping findBinding(String translationKey) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) return null;
        for (KeyMapping binding : client.options.keyMappings) if (binding.getName().equals(translationKey)) return binding;
        return null;
    }

    private static void restoreOne(String id) {
        KeyMapping binding = findBinding(id);
        InputConstants.Key original = originalKeys.remove(id);
        lockedLayout.remove(id);
        if (original != null && binding != null) binding.setKey(original);
    }

    private static void restoreAll() {
        releaseAllKeys();
        for (String id : new ArrayList<>(originalKeys.keySet())) restoreOne(id);
        enabledKeys.clear();
        lockedLayout.clear();
        KeyMapping.resetMapping();
        releaseAllKeys();
    }

    private static int showAvailable(String filter) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options == null) return 0;
        String needle = filter.toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (KeyMapping binding : client.options.keyMappings) {
            String id = binding.getName();
            if (needle.isEmpty() || id.toLowerCase(Locale.ROOT).contains(needle)) ids.add(id);
        }
        ids.sort(String::compareTo);
        client.player.sendSystemMessage(Component.literal("Registered KeyMappings (" + ids.size() + "):"));
        for (String id : ids) client.player.sendSystemMessage(Component.literal(" - " + id));
        return ids.size();
    }

    private static void releaseAllKeys() {
        KeyMapping.releaseAll();
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

    private static final class RuntimeEvents {
        @SubscribeEvent
        public void onClientCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(Commands.literal("randomkeysclient")
                    .then(Commands.literal("available")
                            .executes(ctx -> showAvailable(""))
                            .then(Commands.argument("filter", StringArgumentType.greedyString())
                                    .executes(ctx -> showAvailable(StringArgumentType.getString(ctx, "filter"))))));
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft client = Minecraft.getInstance();
            boolean inWorld = client.level != null && client.player != null;
            if (inWorld) enforceLockedBindings(true);
            if (wasInWorld && !inWorld) restoreAll();
            wasInWorld = inWorld;
        }
    }
}
