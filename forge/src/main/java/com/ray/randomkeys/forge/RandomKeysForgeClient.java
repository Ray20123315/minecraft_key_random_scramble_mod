package com.ray.randomkeys.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
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
import java.util.Set;

@Mod.EventBusSubscriber(modid = RandomKeysForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RandomKeysForgeClient {
    private static final Set<String> enabledKeys = new LinkedHashSet<>();
    private static final Map<String, InputConstants.Key> originalKeys = new HashMap<>();
    private static final Map<String, InputConstants.Key> lockedLayout = new LinkedHashMap<>();
    private static final List<String> LEFT_HUD_KEYS = List.of(
            "key.forward", "key.left", "key.back", "key.right",
            "key.jump", "key.attack", "key.use", "key.sneak",
            "key.inventory", "key.drop", "key.swapOffhand"
    );
    private static final List<String> RIGHT_HUD_KEYS = List.of(
            "key.hotbar.1", "key.hotbar.2", "key.hotbar.3", "key.hotbar.4", "key.hotbar.5",
            "key.hotbar.6", "key.hotbar.7", "key.hotbar.8", "key.hotbar.9"
    );
    private static final int HUD_UNCHANGED_COLOR = 0x55FF55;
    private static final int HUD_CHANGED_COLOR = 0xFF5555;
    private static long lastLockWarningMs;
    private static boolean wasInWorld;

    static {
        MinecraftForge.EVENT_BUS.register(new RuntimeEvents());
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("key_hud", (gui, graphics, partialTick, screenWidth, screenHeight) -> renderHud(graphics, screenWidth));
    }

    public static void applySync(List<String> newKeys, Map<String, String> serverLayout) {
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

        // Apply persisted server state before any client snapshot is uploaded.
        applyLayout(serverLayout, null, false);
        enforceLockedBindings(false);
        sendSnapshot();
    }

    public static void applyMutation(String id, String keyToken) {
        if (!enabledKeys.contains(id)) return;
        KeyMapping binding = findBinding(id);
        if (binding == null) return;
        InputConstants.Key next = decodeKey(keyToken);
        if (next == null) return;

        releaseAllKeys();
        binding.setKey(next);
        lockedLayout.put(id, next);
        KeyMapping.resetMapping();
        releaseAllKeys();

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(Component.translatable("random_keys_survival.message.key_changed",
                    Component.translatable(binding.getName()), displayKey(binding)), true);
        }
        sendSnapshot();
    }

    public static void applyLayout(Map<String, String> map, String donor, boolean announce) {
        releaseAllKeys();
        for (String id : enabledKeys) {
            String token = map.get(id);
            if (token == null) continue;
            KeyMapping binding = findBinding(id);
            if (binding == null) continue;
            InputConstants.Key key = decodeKey(token);
            if (key == null) continue;
            binding.setKey(key);
            lockedLayout.put(id, key);
        }
        KeyMapping.resetMapping();
        releaseAllKeys();
        Minecraft client = Minecraft.getInstance();
        if (announce && donor != null && client.player != null) {
            client.player.displayClientMessage(Component.translatable("random_keys_survival.message.layout_received", donor), true);
        }
        if (announce) sendSnapshot();
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
                    client.player.displayClientMessage(Component.translatable("random_keys_survival.message.controls_locked"), true);
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
            if (binding != null) map.put(id, encodeKey(binding.getKey()));
        }
        RandomKeysForge.CHANNEL.sendToServer(new RandomKeysForge.SnapshotPacket(map));
    }

    private static String encodeKey(InputConstants.Key key) {
        if (key.equals(InputConstants.UNKNOWN)) return "unbound";
        return key.getType().name() + ":" + key.getValue();
    }

    private static InputConstants.Key decodeKey(String token) {
        if (token == null) return null;
        if (token.equals("unbound")) return InputConstants.UNKNOWN;
        int colon = token.indexOf(':');
        if (colon > 0 && colon < token.length() - 1) {
            try {
                InputConstants.Type type = InputConstants.Type.valueOf(token.substring(0, colon));
                int code = Integer.parseInt(token.substring(colon + 1));
                return type.getOrCreate(code);
            } catch (IllegalArgumentException ignored) {
            }
        }
        try {
            return InputConstants.getKey(token);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void renderHud(GuiGraphics graphics, int screenWidth) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) return;

        List<String> leftIds = new ArrayList<>();
        List<String> rightIds = new ArrayList<>();
        for (String id : LEFT_HUD_KEYS) if (enabledKeys.contains(id) && findBinding(id) != null) leftIds.add(id);
        for (String id : RIGHT_HUD_KEYS) if (enabledKeys.contains(id) && findBinding(id) != null) rightIds.add(id);
        for (String id : enabledKeys) {
            if (LEFT_HUD_KEYS.contains(id) || RIGHT_HUD_KEYS.contains(id)) continue;
            if (findBinding(id) != null) rightIds.add(id);
        }
        if (leftIds.isEmpty() && rightIds.isEmpty()) return;

        int padding = 5;
        int gap = 10;
        int lineHeight = client.font.lineHeight + 2;
        int leftWidth = hudColumnWidth(client, leftIds);
        int rightWidth = hudColumnWidth(client, rightIds);
        int columnGap = !leftIds.isEmpty() && !rightIds.isEmpty() ? gap : 0;
        int totalWidth = leftWidth + columnGap + rightWidth;
        int rows = Math.max(leftIds.size(), rightIds.size());
        int x = screenWidth - totalWidth - padding * 2 - 4;
        int y = 6;
        int height = rows * lineHeight + padding * 2;
        graphics.fill(x - 2, y - 2, screenWidth - 4, y + height, 0x88000000);

        int leftX = x + padding;
        int rightX = leftX + leftWidth + columnGap;
        drawHudColumn(graphics, client, leftIds, leftX, y + padding, lineHeight);
        drawHudColumn(graphics, client, rightIds, rightX, y + padding, lineHeight);
    }

    private static int hudColumnWidth(Minecraft client, List<String> ids) {
        int width = 0;
        for (String id : ids) {
            KeyMapping binding = findBinding(id);
            if (binding != null) width = Math.max(width, client.font.width(hudLine(binding)));
        }
        return width;
    }

    private static void drawHudColumn(GuiGraphics graphics, Minecraft client, List<String> ids, int x, int y, int lineHeight) {
        int ty = y;
        for (String id : ids) {
            KeyMapping binding = findBinding(id);
            if (binding == null) continue;
            InputConstants.Key original = originalKeys.get(id);
            int color = original != null && original.equals(binding.getKey()) ? HUD_UNCHANGED_COLOR : HUD_CHANGED_COLOR;
            graphics.drawString(client.font, hudLine(binding), x, ty, color, true);
            ty += lineHeight;
        }
    }

    private static Component hudLine(KeyMapping binding) {
        return Component.translatable(binding.getName()).copy()
                .append(Component.literal(": "))
                .append(displayKey(binding));
    }

    private static Component displayKey(KeyMapping binding) {
        InputConstants.Key key = binding.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM && key.getValue() >= 320 && key.getValue() <= 329) {
            return Component.translatable("random_keys_survival.key.numpad", key.getValue() - 320);
        }
        return binding.getTranslatedKeyMessage();
    }

    private static boolean shouldBlockHotbarScroll() {
        Minecraft client = Minecraft.getInstance();
        return enabledKeys.stream().anyMatch(id -> id.startsWith("key.hotbar.")) && client.player != null && client.screen == null;
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
        client.player.sendSystemMessage(Component.translatable("random_keys_survival.message.available_header", ids.size()));
        for (String id : ids) client.player.sendSystemMessage(Component.literal(" - " + id));
        return ids.size();
    }

    private static void releaseAllKeys() {
        KeyMapping.releaseAll();
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
        public void onMouseScroll(InputEvent.MouseScrollingEvent event) {
            if (shouldBlockHotbarScroll()) event.setCanceled(true);
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
