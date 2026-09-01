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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
            "key.jump", "key.sneak", "key.sprint", "key.inventory",
            "key.swapOffhand", "key.drop", "key.playerlist", "key.pickItem"
    );

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> enabledKeys = new LinkedHashSet<>();
    private static final Map<UUID, LinkedHashMap<String, String>> snapshots = new ConcurrentHashMap<>();
    private static int ticksUntilSwap = SWAP_INTERVAL_TICKS;

    public RandomKeysForge() {
        loadConfig();
        int id = 0;
        CHANNEL.registerMessage(id++, WhitelistPacket.class, WhitelistPacket::encode, WhitelistPacket::decode, WhitelistPacket::handle);
        CHANNEL.registerMessage(id++, MutatePacket.class, MutatePacket::encode, MutatePacket::decode, MutatePacket::handle);
        CHANNEL.registerMessage(id++, LayoutPacket.class, LayoutPacket::encode, LayoutPacket::decode, LayoutPacket::handle);
        CHANNEL.registerMessage(id, SnapshotPacket.class, SnapshotPacket::encode, SnapshotPacket::decode, SnapshotPacket::handle);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendWhitelist(player);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        snapshots.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onHurt(LivingDamageEvent event) {
        if (event.getAmount() > 0.0F && event.getEntity() instanceof ServerPlayer player) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MutatePacket());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (--ticksUntilSwap <= 0) {
            ticksUntilSwap = SWAP_INTERVAL_TICKS;
            rotateLayouts(server);
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("randomkeys")
                .then(Commands.literal("list").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Random Keys: " + String.join(", ", enabledKeys)), false);
                    return 1;
                }))
                .then(Commands.literal("add").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "translation_key");
                            if (enabledKeys.add(key)) {
                                saveConfig();
                                broadcastWhitelist(ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.literal("Added: " + key), true);
                            } else ctx.getSource().sendFailure(Component.literal("Already enabled: " + key));
                            return 1;
                        })))
                .then(Commands.literal("remove").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "translation_key");
                            if (enabledKeys.remove(key)) {
                                saveConfig();
                                broadcastWhitelist(ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.literal("Removed: " + key), true);
                            } else ctx.getSource().sendFailure(Component.literal("Not enabled: " + key));
                            return 1;
                        })))
                .then(Commands.literal("reset").requires(source -> source.hasPermission(2)).executes(ctx -> {
                    enabledKeys.clear();
                    enabledKeys.addAll(DEFAULT_KEYS);
                    saveConfig();
                    broadcastWhitelist(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("Random Keys whitelist reset to defaults."), true);
                    return 1;
                })));
    }

    private static void sendWhitelist(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new WhitelistPacket(new ArrayList<>(enabledKeys)));
    }

    private static void broadcastWhitelist(MinecraftServer server) {
        snapshots.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendWhitelist(player);
    }

    private static void rotateLayouts(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (snapshots.containsKey(player.getUUID())) players.add(player);
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
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> recipient), new LayoutPacket(donorNames.getOrDefault(recipient.getUUID(), "?"), map));
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

    private static Path configPath() { return FMLPaths.CONFIGDIR.get().resolve("random-keys-survival.json"); }

    private static void loadConfig() {
        enabledKeys.clear();
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Config config = GSON.fromJson(reader, Config.class);
                if (config != null && config.enabledKeys != null) enabledKeys.addAll(config.enabledKeys);
            } catch (Exception ignored) { enabledKeys.clear(); }
        }
        if (enabledKeys.isEmpty()) enabledKeys.addAll(DEFAULT_KEYS);
        saveConfig();
    }

    private static void saveConfig() {
        try {
            Files.createDirectories(configPath().getParent());
            try (Writer writer = Files.newBufferedWriter(configPath())) { GSON.toJson(new Config(new ArrayList<>(enabledKeys)), writer); }
        } catch (IOException ignored) { }
    }

    private static void writeMap(FriendlyByteBuf buf, Map<String, String> map) {
        buf.writeVarInt(map.size());
        map.forEach((k, v) -> { buf.writeUtf(k); buf.writeUtf(v); });
    }

    private static LinkedHashMap<String, String> readMap(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), 4096);
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) map.put(buf.readUtf(512), buf.readUtf(512));
        return map;
    }

    private static final class Config {
        List<String> enabledKeys;
        Config(List<String> enabledKeys) { this.enabledKeys = enabledKeys; }
    }

    public record WhitelistPacket(List<String> keys) {
        static void encode(WhitelistPacket msg, FriendlyByteBuf buf) { buf.writeVarInt(msg.keys.size()); msg.keys.forEach(buf::writeUtf); }
        static WhitelistPacket decode(FriendlyByteBuf buf) { int n=Math.min(buf.readVarInt(),4096); List<String> keys=new ArrayList<>(n); for(int i=0;i<n;i++) keys.add(buf.readUtf(512)); return new WhitelistPacket(keys); }
        static void handle(WhitelistPacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> RandomKeysForgeClient.applyWhitelist(msg.keys)); ctx.get().setPacketHandled(true); }
    }

    public record MutatePacket() {
        static void encode(MutatePacket msg, FriendlyByteBuf buf) { }
        static MutatePacket decode(FriendlyByteBuf buf) { return new MutatePacket(); }
        static void handle(MutatePacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(RandomKeysForgeClient::mutateOne); ctx.get().setPacketHandled(true); }
    }

    public record LayoutPacket(String donor, LinkedHashMap<String, String> map) {
        static void encode(LayoutPacket msg, FriendlyByteBuf buf) { buf.writeUtf(msg.donor); writeMap(buf,msg.map); }
        static LayoutPacket decode(FriendlyByteBuf buf) { return new LayoutPacket(buf.readUtf(128), readMap(buf)); }
        static void handle(LayoutPacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> RandomKeysForgeClient.applyLayout(msg.map,msg.donor)); ctx.get().setPacketHandled(true); }
    }

    public record SnapshotPacket(LinkedHashMap<String, String> map) {
        static void encode(SnapshotPacket msg, FriendlyByteBuf buf) { writeMap(buf,msg.map); }
        static SnapshotPacket decode(FriendlyByteBuf buf) { return new SnapshotPacket(readMap(buf)); }
        static void handle(SnapshotPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) ctx.get().enqueueWork(() -> snapshots.put(sender.getUUID(), filterSnapshot(msg.map)));
            ctx.get().setPacketHandled(true);
        }
    }
}
