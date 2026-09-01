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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RandomKeysFabric implements ModInitializer {
    public static final String MOD_ID = "random_keys_survival";
    public static final Identifier WHITELIST_S2C = new Identifier(MOD_ID, "whitelist");
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
    private static int ticksUntilSwap = SWAP_INTERVAL_TICKS;

    @Override
    public void onInitialize() {
        loadConfig();

        ServerPlayNetworking.registerGlobalReceiver(SNAPSHOT_C2S, (server, player, handler, buf, responseSender) -> {
            LinkedHashMap<String, String> incoming = readMap(buf);
            server.execute(() -> snapshots.put(player.getUuid(), filterSnapshot(incoming)));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendWhitelist(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> snapshots.remove(handler.getPlayer().getUuid()));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ticksUntilSwap = SWAP_INTERVAL_TICKS);

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (amount > 0.0F && entity instanceof ServerPlayerEntity player) {
                sendMutate(player);
            }
            return true;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (--ticksUntilSwap <= 0) {
                ticksUntilSwap = SWAP_INTERVAL_TICKS;
                rotateLayouts(server);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("randomkeys")
                        .then(CommandManager.literal("list").executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> Text.literal("Random Keys: " + String.join(", ", enabledKeys)), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("add")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                                    String key = StringArgumentType.getString(ctx, "translation_key");
                                    if (enabledKeys.add(key)) {
                                        saveConfig();
                                        broadcastWhitelist(ctx.getSource().getServer());
                                        ctx.getSource().sendFeedback(() -> Text.literal("Added: " + key), true);
                                    } else {
                                        ctx.getSource().sendFeedback(() -> Text.literal("Already enabled: " + key), false);
                                    }
                                    return 1;
                                })))
                        .then(CommandManager.literal("remove")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                                    String key = StringArgumentType.getString(ctx, "translation_key");
                                    if (enabledKeys.remove(key)) {
                                        saveConfig();
                                        broadcastWhitelist(ctx.getSource().getServer());
                                        ctx.getSource().sendFeedback(() -> Text.literal("Removed: " + key), true);
                                    } else {
                                        ctx.getSource().sendFeedback(() -> Text.literal("Not enabled: " + key), false);
                                    }
                                    return 1;
                                })))
                        .then(CommandManager.literal("reset")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(ctx -> {
                                    enabledKeys.clear();
                                    enabledKeys.addAll(DEFAULT_KEYS);
                                    saveConfig();
                                    broadcastWhitelist(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal("Random Keys whitelist reset to defaults."), true);
                                    return 1;
                                }))
        ));
    }

    private static void sendMutate(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, MUTATE_S2C, PacketByteBufs.empty());
    }

    private static void sendWhitelist(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(enabledKeys.size());
        for (String key : enabledKeys) buf.writeString(key);
        ServerPlayNetworking.send(player, WHITELIST_S2C, buf);
    }

    private static void broadcastWhitelist(MinecraftServer server) {
        snapshots.clear();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sendWhitelist(player);
    }

    private static void rotateLayouts(MinecraftServer server) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (snapshots.containsKey(player.getUuid())) players.add(player);
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
    }

    private static LinkedHashMap<String, String> filterSnapshot(Map<String, String> incoming) {
        LinkedHashMap<String, String> filtered = new LinkedHashMap<>();
        for (String key : enabledKeys) {
            String value = incoming.get(key);
            if (value != null) filtered.put(key, value);
        }
        return filtered;
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
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Config config = GSON.fromJson(reader, Config.class);
                if (config != null && config.enabledKeys != null) enabledKeys.addAll(config.enabledKeys);
            } catch (Exception ignored) {
                enabledKeys.clear();
            }
        }
        if (enabledKeys.isEmpty()) enabledKeys.addAll(DEFAULT_KEYS);
        saveConfig();
    }

    private static void saveConfig() {
        try {
            Files.createDirectories(configPath().getParent());
            try (Writer writer = Files.newBufferedWriter(configPath())) {
                GSON.toJson(new Config(new ArrayList<>(enabledKeys)), writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static final class Config {
        List<String> enabledKeys;
        Config(List<String> enabledKeys) { this.enabledKeys = enabledKeys; }
    }
}
