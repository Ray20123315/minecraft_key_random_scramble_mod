package com.ray.randomkeys.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
            "key.jump", "key.attack", "key.use", "key.sneak",
            "key.inventory", "key.drop", "key.swapOffhand",
            "key.hotbar.1", "key.hotbar.2", "key.hotbar.3", "key.hotbar.4", "key.hotbar.5",
            "key.hotbar.6", "key.hotbar.7", "key.hotbar.8", "key.hotbar.9"
    );

    /** Canonical equal-weight server-generated keyboard outcomes. */
    private static final int[] SAFE_KEY_CODES = {
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

    private static final Set<UUID> maintenanceBypass = ConcurrentHashMap.newKeySet();
    private static final Random RANDOM = new Random();

    @Override
    public void onInitialize() {
        ServerPlayNetworking.registerGlobalReceiver(SNAPSHOT_C2S, (server, player, handler, buf, responseSender) -> {
            LinkedHashMap<String, String> incoming = readMap(buf);
            server.execute(() -> {
                RandomKeysWorldState state = RandomKeysWorldState.get(server);
                if (state.initializeMissingBindings(player.getUuid(), incoming)) sendSync(player);
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            maintenanceBypass.remove(player.getUuid());
            sendSync(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> maintenanceBypass.remove(handler.getPlayer().getUuid()));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            maintenanceBypass.clear();
            RandomKeysWorldState.get(server);
            enforceServerRules(server);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            enforceServerRules(server);
            RandomKeysWorldState state = RandomKeysWorldState.get(server);
            if (state.tickSwapCountdown() <= 0) {
                rotateLayouts(server, state);
                state.resetSwapCountdown();
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("randomkeys")
                    .then(CommandManager.literal("list").executes(ctx -> {
                        RandomKeysWorldState state = RandomKeysWorldState.get(ctx.getSource().getServer());
                        ctx.getSource().sendFeedback(() -> Text.translatable(
                                "random_keys_survival.command.list", String.join(", ", state.enabledKeys())), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("add")
                            .requires(source -> source.hasPermissionLevel(2))
                            .then(CommandManager.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                                String key = StringArgumentType.getString(ctx, "translation_key").trim();
                                if (!RandomKeysWorldState.isValidTranslationKey(key)) return 0;
                                RandomKeysWorldState state = RandomKeysWorldState.get(ctx.getSource().getServer());
                                if (state.addEnabledKey(key)) {
                                    broadcastSync(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.translatable("random_keys_survival.command.added", key), true);
                                } else {
                                    ctx.getSource().sendError(Text.translatable("random_keys_survival.command.already_enabled", key));
                                }
                                return 1;
                            })))
                    .then(CommandManager.literal("remove")
                            .requires(source -> source.hasPermissionLevel(2))
                            .then(CommandManager.argument("translation_key", StringArgumentType.greedyString()).executes(ctx -> {
                                String key = StringArgumentType.getString(ctx, "translation_key").trim();
                                RandomKeysWorldState state = RandomKeysWorldState.get(ctx.getSource().getServer());
                                if (state.removeEnabledKey(key)) {
                                    broadcastSync(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.translatable("random_keys_survival.command.removed", key), true);
                                } else {
                                    ctx.getSource().sendError(Text.translatable("random_keys_survival.command.not_enabled", key));
                                }
                                return 1;
                            })))
                    .then(CommandManager.literal("reset")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(ctx -> {
                                RandomKeysWorldState state = RandomKeysWorldState.get(ctx.getSource().getServer());
                                state.resetEnabledKeys();
                                broadcastSync(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.translatable("random_keys_survival.command.reset"), true);
                                return 1;
                            })));

            dispatcher.register(CommandManager.literal("!c")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                        UUID uuid = player.getUuid();
                        if (maintenanceBypass.add(uuid)) {
                            ctx.getSource().sendFeedback(() -> Text.translatable("random_keys_survival.maintenance.enabled"), false);
                        } else {
                            maintenanceBypass.remove(uuid);
                            if (player.interactionManager.getGameMode() != GameMode.SURVIVAL) player.changeGameMode(GameMode.SURVIVAL);
                            ctx.getSource().sendFeedback(() -> Text.translatable("random_keys_survival.maintenance.disabled"), false);
                        }
                        return 1;
                    }));
        });
    }

    public static void onActualHealthDamage(ServerPlayerEntity player) {
        if (player.getServer() != null) mutateOne(player, RandomKeysWorldState.get(player.getServer()));
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

    private static void mutateOne(ServerPlayerEntity player, RandomKeysWorldState state) {
        LinkedHashMap<String, String> layout = state.layout(player.getUuid());
        if (layout.isEmpty()) return;

        List<String> candidates = new ArrayList<>();
        for (String id : state.enabledKeys()) if (layout.containsKey(id)) candidates.add(id);
        if (candidates.isEmpty()) return;

        String selected = candidates.get(RANDOM.nextInt(candidates.size()));
        String current = layout.get(selected);
        int outcome = RANDOM.nextInt(SAFE_KEY_CODES.length + 1);
        String next = outcome == SAFE_KEY_CODES.length
                ? "unbound"
                : "KEYSYM:" + SAFE_KEY_CODES[outcome];

        if (next.equals(current)) return;
        if (!state.setBinding(player.getUuid(), selected, next)) return;

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(selected);
        buf.writeString(next);
        ServerPlayNetworking.send(player, MUTATE_S2C, buf);
    }

    private static void sendSync(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        RandomKeysWorldState state = RandomKeysWorldState.get(server);
        PacketByteBuf buf = PacketByteBufs.create();
        List<String> enabledKeys = state.enabledKeys();
        buf.writeVarInt(enabledKeys.size());
        for (String key : enabledKeys) buf.writeString(key);
        writeMap(buf, state.layout(player.getUuid()));
        ServerPlayNetworking.send(player, SYNC_S2C, buf);
    }

    private static void broadcastSync(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sendSync(player);
    }

    private static void rotateLayouts(MinecraftServer server, RandomKeysWorldState state) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (state.isLayoutComplete(player.getUuid())) players.add(player);
        }
        if (players.size() < 2) return;

        Collections.shuffle(players, RANDOM);
        Map<UUID, LinkedHashMap<String, String>> next = new LinkedHashMap<>();
        Map<UUID, String> donorNames = new LinkedHashMap<>();

        for (int i = 0; i < players.size(); i++) {
            ServerPlayerEntity recipient = players.get(i);
            ServerPlayerEntity donor = players.get((i + 1) % players.size());
            next.put(recipient.getUuid(), state.layout(donor.getUuid()));
            donorNames.put(recipient.getUuid(), donor.getGameProfile().getName());
        }

        for (ServerPlayerEntity recipient : players) {
            LinkedHashMap<String, String> map = next.get(recipient.getUuid());
            if (map == null) continue;
            state.replaceLayout(recipient.getUuid(), map);
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(donorNames.getOrDefault(recipient.getUuid(), "?"));
            writeMap(buf, map);
            ServerPlayNetworking.send(recipient, APPLY_LAYOUT_S2C, buf);
        }
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
}
