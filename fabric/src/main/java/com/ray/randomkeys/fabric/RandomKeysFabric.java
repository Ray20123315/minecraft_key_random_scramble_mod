package com.ray.randomkeys.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RandomKeysFabric implements ModInitializer {
    public static final String MOD_ID = "random_keys_survival";
    public static final Identifier SYNC_S2C = new Identifier(MOD_ID, "sync");
    public static final Identifier MUTATE_S2C = new Identifier(MOD_ID, "mutate");
    public static final Identifier APPLY_LAYOUT_S2C = new Identifier(MOD_ID, "apply_layout");
    public static final Identifier SNAPSHOT_C2S = new Identifier(MOD_ID, "snapshot");
    public static final int SWAP_INTERVAL_TICKS = 20 * 60 * 3;

    public static final List<String> DEFAULT_KEYS = List.of(
            "key.forward", "key.left", "key.back", "key.right",
            "key.jump", "key.sneak", "key.sprint", "key.inventory",
            "key.swapOffhand", "key.drop", "key.playerlist", "key.pickItem"
    );

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> enabledKeys = new LinkedHashSet<>();
    private static final Map<UUID, LinkedHashMap<String, String>> snapshots = new ConcurrentHashMap<>();
    private static final Set<UUID> maintenanceBypass = ConcurrentHashMap.newKeySet();
    private static final Random RANDOM = new Random();
    private static final int[] RANDOM_KEY_CODES = buildKeyboardPool();
    private static int ticksUntilSwap = SWAP_INTERVAL_TICKS;

    @Override
    public void onInitialize() {
        loadConfig();

        ServerPlayNetworking.registerGlobalReceiver(SNAPSHOT_C2S, (server, player, handler, buf, responseSender) -> {
            LinkedHashMap<String, String> incoming = readMap(buf);
            server.execute(() -> mergeSnapshot(player.getUuid(), incoming));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            maintenanceBypass.remove(player.getUuid());
            sendSync(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> maintenanceBypass.remove(handler.getPlayer().getUuid()));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ticksUntilSwap = SWAP_INTERVAL_TICKS;
            maintenanceBypass.clear();
            enforceServerRules(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveConfig());

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (amount > 0.0F && entity instanceof ServerPlayerEntity player) mutateOne(player);
            return true;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            enforceServerRules(server);
            if (--ticksUntilSwap <= 0) {
                ticksUntilSwap = SWAP_INTERVAL_TICKS;
                rotateLayouts(server);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("randomkeys")
                    .then(CommandManager.literal("list").executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal("Random Keys: " + String.join(", ", enabledKeys)), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("add")
                            .requires(source -> source.hasPermissionLevel(2))
                            .then(CommandManager.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                                String key = StringArgumentType.getString(ctx, "translation_key").trim();
                                if (key.isEmpty()) return 0;
                                if (enabledKeys.add(key)) {
                                    saveConfig();
                                    broadcastSync(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal("Added: " + key), true);
                                } else {
                                    ctx.getSource().sendError(Text.literal("Already enabled: " + key));
                                }
                                return 1;
                            })))
                    .then(CommandManager.literal("remove")
                            .requires(source -> source.hasPermissionLevel(2))
                            .then(CommandManager.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                                String key = StringArgumentType.getString(ctx, "translation_key").trim();
                                if (enabledKeys.remove(key)) {
                                    removeKeyFromSnapshots(key);
                                    saveConfig();
                                    broadcastSync(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal("Removed: " + key), true);
                                } else {
                                    ctx.getSource().sendError(Text.literal("Not enabled: " + key));
                                }
                                return 1;
                            })))
                    .then(CommandManager.literal("reset")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(ctx -> {
                                enabledKeys.clear();
                                enabledKeys.addAll(DEFAULT_KEYS);
                                trimSnapshotsToWhitelist();
                                saveConfig();
                                broadcastSync(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("Random Keys whitelist reset to defaults."), true);
                                return 1;
                            })));

            dispatcher.register(CommandManager.literal("!c")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                        UUID uuid = player.getUuid();
                        if (maintenanceBypass.add(uuid)) {
                            ctx.getSource().sendFeedback(() -> Text.literal("維護模式已啟用：現在可使用創造／冒險／旁觀；離線後自動失效。"), false);
                        } else {
                            maintenanceBypass.remove(uuid);
                            if (player.interactionManager.getGameMode() != GameMode.SURVIVAL) player.changeGameMode(GameMode.SURVIVAL);
                            ctx.getSource().sendFeedback(() -> Text.literal("維護模式已關閉：已鎖回生存模式。"), false);
                        }
                        return 1;
                    }));
        });
    }

    private static void enforceServerRules(MinecraftServer server) {
        GameRules.BooleanRule keepInventory = server.getGameRules().get(GameRules.KEEP_INVENTORY);
        if (keepInventory.get()) keepInventory.set(false, server);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!maintenanceBypass.contains(player.getUuid()) && player.interactionManager.getGameMode() != GameMode.SURVIVAL) {
                player.changeGameMode(GameMode.SURVIVAL);
            }
        }
    }

    private static void mutateOne(ServerPlayerEntity player) {
        LinkedHashMap<String, String> layout = snapshots.get(player.getUuid());
        if (layout == null || layout.isEmpty()) return;

        List<String> candidates = new ArrayList<>();
        for (String id : enabledKeys) if (layout.containsKey(id)) candidates.add(id);
        if (candidates.isEmpty()) return;

        String selected = candidates.get(RANDOM.nextInt(candidates.size()));
        String current = layout.get(selected);
        String next;
        do {
            if (RANDOM.nextInt(RANDOM_KEY_CODES.length + 1) == RANDOM_KEY_CODES.length) {
                next = "unbound";
            } else {
                next = "KEYSYM:" + RANDOM_KEY_CODES[RANDOM.nextInt(RANDOM_KEY_CODES.length)];
            }
        } while (next.equals(current));

        layout.put(selected, next);
        saveConfig();

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(selected);
        buf.writeString(next);
        ServerPlayNetworking.send(player, MUTATE_S2C, buf);
    }

    private static void sendSync(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(enabledKeys.size());
        for (String key : enabledKeys) buf.writeString(key);
        writeMap(buf, snapshots.getOrDefault(player.getUuid(), new LinkedHashMap<>()));
        ServerPlayNetworking.send(player, SYNC_S2C, buf);
    }

    private static void broadcastSync(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sendSync(player);
    }

    private static void rotateLayouts(MinecraftServer server) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            LinkedHashMap<String, String> map = snapshots.get(player.getUuid());
            if (map != null && !map.isEmpty()) players.add(player);
        }
        if (players.size() < 2) return;

        Collections.shuffle(players);
        Map<UUID, LinkedHashMap<String, String>> next = new LinkedHashMap<>();
        Map<UUID, String> donorNames = new LinkedHashMap<>();

        for (int i = 0; i < players.size(); i++) {
            ServerPlayerEntity recipient = players.get(i);
            ServerPlayerEntity donor = players.get((i + 1) % players.size());
            LinkedHashMap<String, String> donorSnapshot = snapshots.get(donor.getUuid());
            if (donorSnapshot != null) {
                next.put(recipient.getUuid(), new LinkedHashMap<>(donorSnapshot));
                donorNames.put(recipient.getUuid(), donor.getGameProfile().getName());
            }
        }

        for (ServerPlayerEntity recipient : players) {
            LinkedHashMap<String, String> map = next.get(recipient.getUuid());
            if (map == null) continue;
            snapshots.put(recipient.getUuid(), new LinkedHashMap<>(map));
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(donorNames.getOrDefault(recipient.getUuid(), "?"));
            writeMap(buf, map);
            ServerPlayNetworking.send(recipient, APPLY_LAYOUT_S2C, buf);
        }
        saveConfig();
    }

    private static void mergeSnapshot(UUID uuid, Map<String, String> incoming) {
        LinkedHashMap<String, String> existing = snapshots.computeIfAbsent(uuid, ignored -> new LinkedHashMap<>());
        for (String key : enabledKeys) {
            String value = incoming.get(key);
            if (value != null) existing.put(key, value);
        }
        existing.keySet().removeIf(key -> !enabledKeys.contains(key));
        saveConfig();
    }

    private static void removeKeyFromSnapshots(String key) {
        for (LinkedHashMap<String, String> map : snapshots.values()) map.remove(key);
    }

    private static void trimSnapshotsToWhitelist() {
        for (LinkedHashMap<String, String> map : snapshots.values()) map.keySet().removeIf(key -> !enabledKeys.contains(key));
    }

    public static void writeMap(PacketByteBuf buf, Map<String, String> map) {
        buf.writeVarInt(map.size());
        map.forEach((k, v) -> {
            buf.writeString(k);
            buf.writeString(v);
        });
    }

    public static LinkedHashMap<String, String> readMap(PacketByteBuf buf) {
        int size = Math.min(buf.readVarInt(), 4096);
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) map.put(buf.readString(512), buf.readString(512));
        return map;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("random-keys-survival.json");
    }

    private static void loadConfig() {
        enabledKeys.clear();
        snapshots.clear();
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Config config = GSON.fromJson(reader, Config.class);
                if (config != null) {
                    if (config.enabledKeys != null) enabledKeys.addAll(config.enabledKeys);
                    if (config.layouts != null) {
                        config.layouts.forEach((id, map) -> {
                            try {
                                if (map != null) snapshots.put(UUID.fromString(id), new LinkedHashMap<>(map));
                            } catch (IllegalArgumentException ignored) {
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
                enabledKeys.clear();
                snapshots.clear();
            }
        }
        if (enabledKeys.isEmpty()) enabledKeys.addAll(DEFAULT_KEYS);
        trimSnapshotsToWhitelist();
        saveConfig();
    }

    private static synchronized void saveConfig() {
        try {
            Files.createDirectories(configPath().getParent());
            LinkedHashMap<String, LinkedHashMap<String, String>> layouts = new LinkedHashMap<>();
            snapshots.forEach((uuid, map) -> layouts.put(uuid.toString(), new LinkedHashMap<>(map)));
            try (Writer writer = Files.newBufferedWriter(configPath())) {
                GSON.toJson(new Config(new ArrayList<>(enabledKeys), layouts), writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static int[] buildKeyboardPool() {
        List<Integer> keys = new ArrayList<>();
        for (int k = 32; k <= 96; k++) keys.add(k);
        keys.add(161);
        keys.add(162);
        for (int k = 257; k <= 269; k++) keys.add(k); // Escape (256) intentionally excluded.
        for (int k = 280; k <= 284; k++) keys.add(k);
        for (int k = 290; k <= 314; k++) keys.add(k);
        for (int k = 320; k <= 336; k++) keys.add(k);
        for (int k = 340; k <= 348; k++) keys.add(k);
        return keys.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final class Config {
        List<String> enabledKeys;
        Map<String, LinkedHashMap<String, String>> layouts;

        Config(List<String> enabledKeys, Map<String, LinkedHashMap<String, String>> layouts) {
            this.enabledKeys = enabledKeys;
            this.layouts = layouts;
        }
    }
}
