package com.ray.randomkeys.forge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

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
import java.util.function.Supplier;

@Mod(RandomKeysForge.MOD_ID)
public final class RandomKeysForge {
    public static final String MOD_ID = "random_keys_survival";
    public static final int SWAP_INTERVAL_TICKS = 20 * 60 * 3;
    public static final List<String> DEFAULT_KEYS = List.of(
            "key.forward", "key.left", "key.back", "key.right",
            "key.jump", "key.attack", "key.use", "key.sneak",
            "key.inventory", "key.drop", "key.swapOffhand",
            "key.hotbar.1", "key.hotbar.2", "key.hotbar.3", "key.hotbar.4", "key.hotbar.5",
            "key.hotbar.6", "key.hotbar.7", "key.hotbar.8", "key.hotbar.9"
    );

    private static final String PROTOCOL = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> enabledKeys = new LinkedHashSet<>();
    private static final Map<UUID, LinkedHashMap<String, String>> snapshots = new ConcurrentHashMap<>();
    private static final Set<UUID> maintenanceBypass = ConcurrentHashMap.newKeySet();
    private static final Random RANDOM = new Random();
    private static final int[] RANDOM_KEY_CODES = buildKeyboardPool();
    private static int ticksUntilSwap = SWAP_INTERVAL_TICKS;

    public RandomKeysForge() {
        loadConfig();
        int id = 0;
        CHANNEL.registerMessage(id++, SyncPacket.class, SyncPacket::encode, SyncPacket::decode, SyncPacket::handle);
        CHANNEL.registerMessage(id++, MutatePacket.class, MutatePacket::encode, MutatePacket::decode, MutatePacket::handle);
        CHANNEL.registerMessage(id++, LayoutPacket.class, LayoutPacket::encode, LayoutPacket::decode, LayoutPacket::handle);
        CHANNEL.registerMessage(id, SnapshotPacket.class, SnapshotPacket::encode, SnapshotPacket::decode, SnapshotPacket::handle);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ticksUntilSwap = SWAP_INTERVAL_TICKS;
        maintenanceBypass.clear();
        enforceServerRules(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        saveConfig();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            maintenanceBypass.remove(player.getUUID());
            sendSync(player);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        maintenanceBypass.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onHurt(LivingDamageEvent event) {
        if (event.getAmount() > 0.0F && event.getEntity() instanceof ServerPlayer player) mutateOne(player);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        enforceServerRules(server);
        if (--ticksUntilSwap <= 0) {
            ticksUntilSwap = SWAP_INTERVAL_TICKS;
            rotateLayouts(server);
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("randomkeys")
                .then(Commands.literal("list").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.command.list", String.join(", ", enabledKeys)), false);
                    return 1;
                }))
                .then(Commands.literal("add").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "translation_key").trim();
                            if (key.isEmpty()) return 0;
                            if (enabledKeys.add(key)) {
                                saveConfig();
                                broadcastSync(ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.command.added", key), true);
                            } else {
                                ctx.getSource().sendFailure(Component.translatable("random_keys_survival.command.already_enabled", key));
                            }
                            return 1;
                        })))
                .then(Commands.literal("remove").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "translation_key").trim();
                            if (enabledKeys.remove(key)) {
                                removeKeyFromSnapshots(key);
                                saveConfig();
                                broadcastSync(ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.command.removed", key), true);
                            } else {
                                ctx.getSource().sendFailure(Component.translatable("random_keys_survival.command.not_enabled", key));
                            }
                            return 1;
                        })))
                .then(Commands.literal("reset").requires(source -> source.hasPermission(2)).executes(ctx -> {
                    enabledKeys.clear();
                    enabledKeys.addAll(DEFAULT_KEYS);
                    trimSnapshotsToWhitelist();
                    saveConfig();
                    broadcastSync(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.command.reset"), true);
                    return 1;
                })));

        event.getDispatcher().register(Commands.literal("!c")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    UUID uuid = player.getUUID();
                    if (maintenanceBypass.add(uuid)) {
                        ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.maintenance.enabled"), false);
                    } else {
                        maintenanceBypass.remove(uuid);
                        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) player.setGameMode(GameType.SURVIVAL);
                        ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.maintenance.disabled"), false);
                    }
                    return 1;
                }));
    }

    private static void enforceServerRules(MinecraftServer server) {
        GameRules.BooleanValue keepInventory = server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        if (keepInventory.get()) keepInventory.set(false, server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!maintenanceBypass.contains(player.getUUID()) && player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
                player.setGameMode(GameType.SURVIVAL);
            }
        }
    }

    private static void mutateOne(ServerPlayer player) {
        LinkedHashMap<String, String> layout = snapshots.get(player.getUUID());
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
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MutatePacket(selected, next));
    }

    private static void sendSync(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncPacket(new ArrayList<>(enabledKeys), new LinkedHashMap<>(snapshots.getOrDefault(player.getUUID(), new LinkedHashMap<>()))));
    }

    private static void broadcastSync(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendSync(player);
    }

    private static void rotateLayouts(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LinkedHashMap<String, String> map = snapshots.get(player.getUUID());
            if (map != null && !map.isEmpty()) players.add(player);
        }
        if (players.size() < 2) return;

        Collections.shuffle(players);
        Map<UUID, LinkedHashMap<String, String>> next = new LinkedHashMap<>();
        Map<UUID, String> donorNames = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer recipient = players.get(i);
            ServerPlayer donor = players.get((i + 1) % players.size());
            LinkedHashMap<String, String> donorSnapshot = snapshots.get(donor.getUUID());
            if (donorSnapshot != null) {
                next.put(recipient.getUUID(), new LinkedHashMap<>(donorSnapshot));
                donorNames.put(recipient.getUUID(), donor.getGameProfile().getName());
            }
        }

        for (ServerPlayer recipient : players) {
            LinkedHashMap<String, String> map = next.get(recipient.getUUID());
            if (map == null) continue;
            snapshots.put(recipient.getUUID(), new LinkedHashMap<>(map));
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> recipient),
                    new LayoutPacket(donorNames.getOrDefault(recipient.getUUID(), "?"), map));
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

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("random-keys-survival.json");
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

    private static void writeMap(FriendlyByteBuf buf, Map<String, String> map) {
        buf.writeVarInt(map.size());
        map.forEach((k, v) -> {
            buf.writeUtf(k);
            buf.writeUtf(v);
        });
    }

    private static LinkedHashMap<String, String> readMap(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), 4096);
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) map.put(buf.readUtf(512), buf.readUtf(512));
        return map;
    }

    private static int[] buildKeyboardPool() {
        List<Integer> keys = new ArrayList<>();
        for (int k = 32; k <= 96; k++) keys.add(k);
        keys.add(161);
        keys.add(162);
        for (int k = 257; k <= 269; k++) keys.add(k); // Escape (256) intentionally excluded.
        for (int k = 280; k <= 284; k++) keys.add(k);
        for (int k = 290; k <= 314; k++) keys.add(k);
        for (int k = 320; k <= 336; k++) keys.add(k); // Keypad 0-9 and keypad operators; valid even if hardware lacks a numpad.
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

    public record SyncPacket(List<String> keys, LinkedHashMap<String, String> layout) {
        static void encode(SyncPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.keys.size());
            msg.keys.forEach(buf::writeUtf);
            writeMap(buf, msg.layout);
        }

        static SyncPacket decode(FriendlyByteBuf buf) {
            int n = Math.min(buf.readVarInt(), 4096);
            List<String> keys = new ArrayList<>(n);
            for (int i = 0; i < n; i++) keys.add(buf.readUtf(512));
            return new SyncPacket(keys, readMap(buf));
        }

        static void handle(SyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> RandomKeysForgeClient.applySync(msg.keys, msg.layout));
            ctx.get().setPacketHandled(true);
        }
    }

    public record MutatePacket(String id, String keyToken) {
        static void encode(MutatePacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.id);
            buf.writeUtf(msg.keyToken);
        }

        static MutatePacket decode(FriendlyByteBuf buf) {
            return new MutatePacket(buf.readUtf(512), buf.readUtf(512));
        }

        static void handle(MutatePacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> RandomKeysForgeClient.applyMutation(msg.id, msg.keyToken));
            ctx.get().setPacketHandled(true);
        }
    }

    public record LayoutPacket(String donor, LinkedHashMap<String, String> map) {
        static void encode(LayoutPacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.donor);
            writeMap(buf, msg.map);
        }

        static LayoutPacket decode(FriendlyByteBuf buf) {
            return new LayoutPacket(buf.readUtf(128), readMap(buf));
        }

        static void handle(LayoutPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> RandomKeysForgeClient.applyLayout(msg.map, msg.donor, true));
            ctx.get().setPacketHandled(true);
        }
    }

    public record SnapshotPacket(LinkedHashMap<String, String> map) {
        static void encode(SnapshotPacket msg, FriendlyByteBuf buf) {
            writeMap(buf, msg.map);
        }

        static SnapshotPacket decode(FriendlyByteBuf buf) {
            return new SnapshotPacket(readMap(buf));
        }

        static void handle(SnapshotPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) ctx.get().enqueueWork(() -> mergeSnapshot(sender.getUUID(), msg.map));
            ctx.get().setPacketHandled(true);
        }
    }
}
