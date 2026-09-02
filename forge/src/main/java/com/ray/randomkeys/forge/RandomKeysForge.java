package com.ray.randomkeys.forge;

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
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    private static final int[] SAFE_KEY_CODES = {
            32, 39, 44, 45, 46, 47,
            48, 49, 50, 51, 52, 53, 54, 55, 56, 57,
            59, 61,
            65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77,
            78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
            91, 92, 93, 96,
            257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269,
            290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301,
            320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 330, 331, 332, 333, 334, 335,
            340, 341, 342, 344, 345, 346
    };
    private static final String PROTOCOL = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();
    private static final Set<UUID> maintenanceBypass = ConcurrentHashMap.newKeySet();
    private static final Random RANDOM = new Random();

    public RandomKeysForge() {
        int id = 0;
        CHANNEL.registerMessage(id++, SyncPacket.class, SyncPacket::encode, SyncPacket::decode, SyncPacket::handle);
        CHANNEL.registerMessage(id++, MutatePacket.class, MutatePacket::encode, MutatePacket::decode, MutatePacket::handle);
        CHANNEL.registerMessage(id++, LayoutPacket.class, LayoutPacket::encode, LayoutPacket::decode, LayoutPacket::handle);
        CHANNEL.registerMessage(id, SnapshotPacket.class, SnapshotPacket::encode, SnapshotPacket::decode, SnapshotPacket::handle);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        maintenanceBypass.clear();
        RandomKeysWorldData.get(event.getServer());
        enforceServerRules(event.getServer());
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFinalHealthDamage(LivingDamageEvent event) {
        if (!event.isCanceled() && event.getAmount() > 0.0F && event.getEntity() instanceof ServerPlayer player) {
            mutateOne(player, RandomKeysWorldData.get(player.getServer()));
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        enforceServerRules(server);
        RandomKeysWorldData state = RandomKeysWorldData.get(server);
        if (state.tickSwapCountdown() <= 0) {
            rotateLayouts(server, state);
            state.resetSwapCountdown();
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("randomkeys")
                .then(Commands.literal("list").executes(ctx -> {
                    RandomKeysWorldData state = RandomKeysWorldData.get(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.command.list", String.join(", ", state.enabledKeys())), false);
                    return 1;
                }))
                .then(Commands.literal("add").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "translation_key").trim();
                            if (!RandomKeysWorldData.isValidTranslationKey(key)) return 0;
                            RandomKeysWorldData state = RandomKeysWorldData.get(ctx.getSource().getServer());
                            if (state.addEnabledKey(key)) {
                                broadcastSync(ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.command.added", key), true);
                            } else ctx.getSource().sendFailure(Component.translatable("random_keys_survival.command.already_enabled", key));
                            return 1;
                        })))
                .then(Commands.literal("remove").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "translation_key").trim();
                            RandomKeysWorldData state = RandomKeysWorldData.get(ctx.getSource().getServer());
                            if (state.removeEnabledKey(key)) {
                                broadcastSync(ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.command.removed", key), true);
                            } else ctx.getSource().sendFailure(Component.translatable("random_keys_survival.command.not_enabled", key));
                            return 1;
                        })))
                .then(Commands.literal("reset").requires(source -> source.hasPermission(2)).executes(ctx -> {
                    RandomKeysWorldData state = RandomKeysWorldData.get(ctx.getSource().getServer());
                    state.resetEnabledKeys();
                    broadcastSync(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.translatable("random_keys_survival.command.reset"), true);
                    return 1;
                })));

        event.getDispatcher().register(Commands.literal("!c").requires(source -> source.hasPermission(2)).executes(ctx -> {
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
            if (!maintenanceBypass.contains(player.getUUID()) && player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) player.setGameMode(GameType.SURVIVAL);
        }
    }

    private static void mutateOne(ServerPlayer player, RandomKeysWorldData state) {
        LinkedHashMap<String, String> layout = state.layout(player.getUUID());
        if (layout.isEmpty()) return;
        List<String> candidates = new ArrayList<>();
        for (String id : state.enabledKeys()) if (layout.containsKey(id)) candidates.add(id);
        if (candidates.isEmpty()) return;
        String selected = candidates.get(RANDOM.nextInt(candidates.size()));
        String current = layout.get(selected);
        int outcome = RANDOM.nextInt(SAFE_KEY_CODES.length + 1);
        String next = outcome == SAFE_KEY_CODES.length ? "unbound" : "KEYSYM:" + SAFE_KEY_CODES[outcome];
        if (next.equals(current)) return;
        if (!state.setBinding(player.getUUID(), selected, next)) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MutatePacket(selected, next));
    }

    private static void sendSync(ServerPlayer player) {
        RandomKeysWorldData state = RandomKeysWorldData.get(player.getServer());
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncPacket(state.enabledKeys(), state.layout(player.getUUID())));
    }

    private static void broadcastSync(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendSync(player);
    }

    private static void rotateLayouts(MinecraftServer server, RandomKeysWorldData state) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (state.isLayoutComplete(player.getUUID())) players.add(player);
        if (players.size() < 2) return;
        Collections.shuffle(players, RANDOM);
        Map<UUID, LinkedHashMap<String, String>> next = new LinkedHashMap<>();
        Map<UUID, String> donorNames = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer recipient = players.get(i);
            ServerPlayer donor = players.get((i + 1) % players.size());
            next.put(recipient.getUUID(), state.layout(donor.getUUID()));
            donorNames.put(recipient.getUUID(), donor.getGameProfile().getName());
        }
        for (ServerPlayer recipient : players) {
            LinkedHashMap<String, String> map = next.get(recipient.getUUID());
            if (map == null) continue;
            state.replaceLayout(recipient.getUUID(), map);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> recipient), new LayoutPacket(donorNames.getOrDefault(recipient.getUUID(), "?"), map));
        }
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

    public record SyncPacket(List<String> keys, LinkedHashMap<String, String> layout) {
        static void encode(SyncPacket msg, FriendlyByteBuf buf) { buf.writeVarInt(msg.keys.size()); msg.keys.forEach(buf::writeUtf); writeMap(buf, msg.layout); }
        static SyncPacket decode(FriendlyByteBuf buf) { int n = Math.min(buf.readVarInt(), 4096); List<String> keys = new ArrayList<>(n); for (int i = 0; i < n; i++) keys.add(buf.readUtf(512)); return new SyncPacket(keys, readMap(buf)); }
        static void handle(SyncPacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> RandomKeysForgeClient.applySync(msg.keys, msg.layout)); ctx.get().setPacketHandled(true); }
    }
    public record MutatePacket(String id, String keyToken) {
        static void encode(MutatePacket msg, FriendlyByteBuf buf) { buf.writeUtf(msg.id); buf.writeUtf(msg.keyToken); }
        static MutatePacket decode(FriendlyByteBuf buf) { return new MutatePacket(buf.readUtf(512), buf.readUtf(512)); }
        static void handle(MutatePacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> RandomKeysForgeClient.applyMutation(msg.id, msg.keyToken)); ctx.get().setPacketHandled(true); }
    }
    public record LayoutPacket(String donor, LinkedHashMap<String, String> map) {
        static void encode(LayoutPacket msg, FriendlyByteBuf buf) { buf.writeUtf(msg.donor); writeMap(buf, msg.map); }
        static LayoutPacket decode(FriendlyByteBuf buf) { return new LayoutPacket(buf.readUtf(128), readMap(buf)); }
        static void handle(LayoutPacket msg, Supplier<NetworkEvent.Context> ctx) { ctx.get().enqueueWork(() -> RandomKeysForgeClient.applyLayout(msg.map, msg.donor, true)); ctx.get().setPacketHandled(true); }
    }
    public record SnapshotPacket(LinkedHashMap<String, String> map) {
        static void encode(SnapshotPacket msg, FriendlyByteBuf buf) { writeMap(buf, msg.map); }
        static SnapshotPacket decode(FriendlyByteBuf buf) { return new SnapshotPacket(readMap(buf)); }
        static void handle(SnapshotPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) ctx.get().enqueueWork(() -> {
                RandomKeysWorldData state = RandomKeysWorldData.get(sender.getServer());
                if (state.initializeMissingBindings(sender.getUUID(), msg.map)) sendSync(sender);
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
