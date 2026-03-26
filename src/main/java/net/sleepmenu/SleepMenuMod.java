package net.sleepmenu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class SleepMenuMod implements ModInitializer {
    public static final String MOD_ID = "sleepmenu";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String PERM_USE = "sleepmenu.use";
    private static final String PERM_TIME_DAY = "sleepmenu.time.day";
    private static final String PERM_TIME_MIDNIGHT = "sleepmenu.time.midnight";
    private static final String PERM_TIME_NIGHT = "sleepmenu.time.night";
    private static final String PERM_TIME_NOON = "sleepmenu.time.noon";
    private static final String PERM_WEATHER_CLEAR = "sleepmenu.weather.clear";
    private static final String PERM_WEATHER_RAIN = "sleepmenu.weather.rain";
    private static final String PERM_WEATHER_THUNDER = "sleepmenu.weather.thunder";

    private static final List<MenuAction> MENU_ACTIONS = List.of(
        MenuAction.time("day", "Day", 1000L, PERM_TIME_DAY, "Made it day"),
        MenuAction.time("midnight", "Midnight", 18000L, PERM_TIME_MIDNIGHT, "Made it midnight"),
        MenuAction.time("night", "Night", 13000L, PERM_TIME_NIGHT, "Made it night"),
        MenuAction.time("noon", "Noon", 6000L, PERM_TIME_NOON, "Made it noon"),
        MenuAction.weather("clear", "Clear", false, false, PERM_WEATHER_CLEAR, "Set weather to clear"),
        MenuAction.weather("rain", "Rain", true, false, PERM_WEATHER_RAIN, "Set weather to rain"),
        MenuAction.weather("thunder", "Thunder", true, true, PERM_WEATHER_THUNDER, "Set weather to thunder")
    );

    private static final Map<String, MenuAction> ACTIONS_BY_ID = buildActionIndex();

    private final Map<UUID, PlayerMenuState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActionTickByPlayer = new ConcurrentHashMap<>();

    private SleepMenuConfig config;
    private PermissionService permissionService;

    @Override
    public void onInitialize() {
        config = SleepMenuConfig.load();
        permissionService = new PermissionService(config);

        registerCommands();
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        logInfo("Initialized");
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            literal("sleepmenu")
                .then(literal("reload").executes(context -> {
                    ServerCommandSource source = context.getSource();
                    if (!isAdminSource(source)) {
                        source.sendError(Text.literal("You do not have permission to reload Sleep Menu."));
                        return 0;
                    }

                    reloadConfig();
                    source.sendFeedback(() -> Text.literal("[SleepMenu] Config reloaded."), false);
                    return 1;
                }))
                .then(literal("open").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    return openMenuForPlayer(player, true) ? 1 : 0;
                }))
                .then(literal("set")
                    .then(argument("option", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (MenuAction action : MENU_ACTIONS) {
                                builder.suggest(action.id);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> executeSetCommand(context.getSource(), StringArgumentType.getString(context, "option")))))
        ));
    }

    private int executeSetCommand(ServerCommandSource source, String rawOption) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();

        MenuAction action = ACTIONS_BY_ID.get(rawOption.toLowerCase(Locale.ROOT));
        if (action == null) {
            source.sendError(Text.literal("Unknown sleep menu option."));
            return 0;
        }

        return executeAction(source, player, action, true) ? 1 : 0;
    }

    private boolean isAdminSource(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return true;
        }

        return source.getServer().getPlayerManager().isOperator(new PlayerConfigEntry(player.getGameProfile()));
    }

    private void reloadConfig() {
        this.config = SleepMenuConfig.load();
        this.permissionService = new PermissionService(this.config);
        logInfo("Config reloaded");
    }

    private void onServerTick(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            online.add(uuid);

            PlayerMenuState state = states.computeIfAbsent(uuid, ignored -> new PlayerMenuState());
            boolean onBed = isStandingOnBed(player);

            if (onBed && !state.onBed) {
                state.onBed = true;
                state.hintTicker = 0;
                openMenuForPlayer(player, false);
            } else if (!onBed && state.onBed) {
                state.onBed = false;
                state.hintTicker = 0;
                player.sendMessage(Text.empty(), true);
            }

            if (!onBed) {
                continue;
            }

            if (!permissionService.hasPermission(player, PERM_USE)) {
                if (state.hintTicker % 40 == 0) {
                    player.sendMessage(Text.literal("You do not have permission to use Sleep Menu."), true);
                }
                state.hintTicker++;
                continue;
            }

            if (state.hintTicker % 40 == 0) {
                player.sendMessage(Text.literal("Sleep Menu: click chat buttons, or use /sleepmenu open"), true);
            }
            state.hintTicker++;
        }

        states.keySet().retainAll(online);
        lastActionTickByPlayer.keySet().retainAll(online);
    }

    private boolean openMenuForPlayer(ServerPlayerEntity player, boolean fromCommand) {
        if (!isStandingOnBed(player)) {
            if (fromCommand) {
                player.sendMessage(Text.literal("Stand on a bed to use Sleep Menu."), false);
            }
            return false;
        }

        if (!permissionService.hasPermission(player, PERM_USE)) {
            player.sendMessage(Text.literal("You do not have permission to use Sleep Menu."), false);
            return false;
        }

        player.sendMessage(Text.literal("[Sleep Menu] Choose an option:").formatted(Formatting.LIGHT_PURPLE), false);
        player.sendMessage(buildClickableRow("Time", List.of("day", "midnight", "night", "noon")), false);
        player.sendMessage(buildClickableRow("Weather", List.of("clear", "rain", "thunder")), false);
        return true;
    }

    private MutableText buildClickableRow(String title, List<String> ids) {
        MutableText row = Text.literal(title + ": ").formatted(Formatting.GOLD);

        for (int i = 0; i < ids.size(); i++) {
            MenuAction action = ACTIONS_BY_ID.get(ids.get(i));
            if (action == null) {
                continue;
            }

            row.append(Text.literal("[" + action.label + "]")
                .styled(style -> style
                    .withColor(Formatting.AQUA)
                    .withClickEvent(new ClickEvent.RunCommand("/sleepmenu set " + action.id))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to apply: " + action.label)))));

            if (i < ids.size() - 1) {
                row.append(Text.literal(" ").formatted(Formatting.GRAY));
            }
        }

        return row;
    }

    private boolean executeAction(ServerCommandSource source, ServerPlayerEntity player, MenuAction action, boolean fromDirectSet) {
        MinecraftServer server = source.getServer();
        if (!isStandingOnBed(player)) {
            player.sendMessage(Text.literal("Stand on a bed to use Sleep Menu."), true);
            return false;
        }

        if (!permissionService.hasPermission(player, PERM_USE)) {
            player.sendMessage(Text.literal("You do not have permission to use Sleep Menu."), true);
            return false;
        }

        if (!permissionService.hasPermission(player, action.permissionNode)) {
            player.sendMessage(Text.literal("You do not have permission for this option."), true);
            return false;
        }

        long nowTick = getCurrentServerTick(server);
        long lastTick = lastActionTickByPlayer.getOrDefault(player.getUuid(), Long.MIN_VALUE / 2);
        long elapsed = nowTick - lastTick;
        if (elapsed < config.cooldownTicks) {
            long remaining = config.cooldownTicks - elapsed;
            player.sendMessage(Text.literal("Sleep Menu cooldown: " + remaining + " ticks left."), true);
            return false;
        }

        boolean applied = switch (action.type) {
            case TIME -> setTime(server, action.targetTime);
            case WEATHER -> setWeather(server, action.raining, action.thundering);
        };

        if (!applied) {
            player.sendMessage(Text.literal("Could not apply Sleep Menu action right now."), true);
            return false;
        }

        lastActionTickByPlayer.put(player.getUuid(), nowTick);
        broadcastAction(server, player, action.broadcastMessage);

        if (fromDirectSet) {
            player.sendMessage(Text.literal("Applied: " + action.label), true);
        }

        return true;
    }

    private boolean setTime(MinecraftServer server, long targetTime) {
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) {
            return false;
        }

        long dayTime = Math.floorMod(overworld.getTimeOfDay(), 24000L);
        long delta = targetTime - dayTime;
        if (delta <= 0) {
            delta += 24000L;
        }

        overworld.setTimeOfDay(overworld.getTimeOfDay() + delta);
        return true;
    }

    private boolean setWeather(MinecraftServer server, boolean raining, boolean thundering) {
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) {
            return false;
        }

        if (!raining && !thundering) {
            overworld.setWeather(12000, 0, false, false);
        } else {
            overworld.setWeather(0, 12000, true, thundering);
        }

        return true;
    }

    private long getCurrentServerTick(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        if (overworld != null) {
            return overworld.getTime();
        }
        return System.currentTimeMillis() / 50L;
    }

    private void broadcastAction(MinecraftServer server, ServerPlayerEntity actor, String action) {
        Text message = Text.literal(actor.getName().getString() + ": " + action);
        server.getPlayerManager().broadcast(message, false);
    }

    private boolean isStandingOnBed(ServerPlayerEntity player) {
        BlockState feet = player.getEntityWorld().getBlockState(player.getBlockPos());
        if (feet.isIn(BlockTags.BEDS)) {
            return true;
        }

        BlockState below = player.getEntityWorld().getBlockState(player.getBlockPos().down());
        return below.isIn(BlockTags.BEDS);
    }

    private static Map<String, MenuAction> buildActionIndex() {
        Map<String, MenuAction> map = new HashMap<>();
        for (MenuAction action : MENU_ACTIONS) {
            map.put(action.id, action);
        }
        return map;
    }

    private static void logInfo(String message) {
        LOGGER.info("[SleepMenu] {}", message);
    }

    private static final class PlayerMenuState {
        private boolean onBed;
        private int hintTicker;
    }

    private enum ActionType {
        TIME,
        WEATHER
    }

    private static final class MenuAction {
        private final String id;
        private final String label;
        private final ActionType type;
        private final long targetTime;
        private final boolean raining;
        private final boolean thundering;
        private final String permissionNode;
        private final String broadcastMessage;

        private MenuAction(String id, String label, ActionType type, long targetTime, boolean raining, boolean thundering, String permissionNode, String broadcastMessage) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.targetTime = targetTime;
            this.raining = raining;
            this.thundering = thundering;
            this.permissionNode = permissionNode;
            this.broadcastMessage = broadcastMessage;
        }

        private static MenuAction time(String id, String label, long targetTime, String permissionNode, String broadcastMessage) {
            return new MenuAction(id, label, ActionType.TIME, targetTime, false, false, permissionNode, broadcastMessage);
        }

        private static MenuAction weather(String id, String label, boolean raining, boolean thundering, String permissionNode, String broadcastMessage) {
            return new MenuAction(id, label, ActionType.WEATHER, 0L, raining, thundering, permissionNode, broadcastMessage);
        }
    }

    private static final class PermissionService {
        private final LuckPermsBridge luckPermsBridge;
        private final SleepMenuConfig config;

        private PermissionService(SleepMenuConfig config) {
            this.config = config;
            this.luckPermsBridge = LuckPermsBridge.tryCreate();

            if (luckPermsBridge == null) {
                logInfo("LuckPerms not found, fallback mode: " + config.noLuckPermsAccessMode);
            } else {
                logInfo("LuckPerms detected, permission nodes are active.");
            }
        }

        private boolean hasPermission(ServerPlayerEntity player, String node) {
            if (luckPermsBridge == null) {
                return switch (config.noLuckPermsAccessMode) {
                    case OP_ONLY -> isOperator(player);
                    case EVERYONE -> true;
                };
            }

            return luckPermsBridge.hasPermission(player.getUuid(), node);
        }

        private boolean isOperator(ServerPlayerEntity player) {
            return player.getCommandSource().getServer().getPlayerManager().isOperator(new PlayerConfigEntry(player.getGameProfile()));
        }
    }

    private static final class LuckPermsBridge {
        private final Method providerGet;
        private final Method getUserManager;
        private final Method getUser;
        private final Method loadUser;
        private final Method getQueryOptions;
        private final Method getCachedData;
        private final Method getPermissionData;
        private final Method checkPermission;
        private final Method asBoolean;

        private boolean warningPrinted;

        private LuckPermsBridge(
            Method providerGet,
            Method getUserManager,
            Method getUser,
            Method loadUser,
            Method getQueryOptions,
            Method getCachedData,
            Method getPermissionData,
            Method checkPermission,
            Method asBoolean
        ) {
            this.providerGet = providerGet;
            this.getUserManager = getUserManager;
            this.getUser = getUser;
            this.loadUser = loadUser;
            this.getQueryOptions = getQueryOptions;
            this.getCachedData = getCachedData;
            this.getPermissionData = getPermissionData;
            this.checkPermission = checkPermission;
            this.asBoolean = asBoolean;
        }

        private static LuckPermsBridge tryCreate() {
            try {
                Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
                Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
                Class<?> userManagerClass = Class.forName("net.luckperms.api.model.user.UserManager");
                Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
                Class<?> cachedDataManagerClass = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
                Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
                Class<?> cachedPermissionDataClass = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
                Class<?> tristateClass = Class.forName("net.luckperms.api.util.Tristate");

                Method providerGet = providerClass.getMethod("get");
                Method getUserManager = luckPermsClass.getMethod("getUserManager");
                Method getUser = userManagerClass.getMethod("getUser", UUID.class);
                Method loadUser = userManagerClass.getMethod("loadUser", UUID.class);
                Method getQueryOptions = userClass.getMethod("getQueryOptions");
                Method getCachedData = userClass.getMethod("getCachedData");
                Method getPermissionData = cachedDataManagerClass.getMethod("getPermissionData", queryOptionsClass);
                Method checkPermission = cachedPermissionDataClass.getMethod("checkPermission", String.class);
                Method asBoolean = tristateClass.getMethod("asBoolean");

                return new LuckPermsBridge(
                    providerGet,
                    getUserManager,
                    getUser,
                    loadUser,
                    getQueryOptions,
                    getCachedData,
                    getPermissionData,
                    checkPermission,
                    asBoolean
                );
            } catch (ClassNotFoundException ignored) {
                return null;
            } catch (ReflectiveOperationException e) {
                LOGGER.error("[SleepMenu] Failed to initialize LuckPerms bridge. Fallback mode will be used.", e);
                return null;
            }
        }

        private boolean hasPermission(UUID playerUuid, String node) {
            try {
                Object luckPerms = providerGet.invoke(null);
                Object userManager = getUserManager.invoke(luckPerms);
                Object user = getUser.invoke(userManager, playerUuid);

                if (user == null) {
                    CompletableFuture<?> future = (CompletableFuture<?>) loadUser.invoke(userManager, playerUuid);
                    user = future.getNow(null);
                    if (user == null) {
                        user = future.join();
                    }
                }

                if (user == null) {
                    return false;
                }

                Object queryOptions = getQueryOptions.invoke(user);
                Object cachedDataManager = getCachedData.invoke(user);
                Object cachedPermissionData = getPermissionData.invoke(cachedDataManager, queryOptions);
                Object tristate = checkPermission.invoke(cachedPermissionData, node);

                return (boolean) asBoolean.invoke(tristate);
            } catch (Exception e) {
                if (!warningPrinted) {
                    warningPrinted = true;
                    LOGGER.warn("[SleepMenu] LuckPerms permission check failed. Fallback mode will be used.", e);
                }
                return true;
            }
        }
    }

    private enum NoLuckPermsAccessMode {
        EVERYONE,
        OP_ONLY
    }

    private static final class SleepMenuConfig {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("sleepmenu.json");

        private int cooldownTicks = 400;
        private String noLuckPermsAccess = "EVERYONE";
        private transient NoLuckPermsAccessMode noLuckPermsAccessMode = NoLuckPermsAccessMode.EVERYONE;

        private static SleepMenuConfig load() {
            SleepMenuConfig config = null;

            if (Files.exists(CONFIG_PATH)) {
                try {
                    String raw = Files.readString(CONFIG_PATH);
                    config = GSON.fromJson(raw, SleepMenuConfig.class);
                } catch (IOException | JsonSyntaxException e) {
                    LOGGER.warn("[SleepMenu] Failed to read config, using defaults.", e);
                }
            }

            if (config == null) {
                config = new SleepMenuConfig();
            }

            config.normalize();
            config.save();
            return config;
        }

        private void normalize() {
            if (cooldownTicks < 0) {
                cooldownTicks = 0;
            }

            try {
                noLuckPermsAccessMode = NoLuckPermsAccessMode.valueOf(noLuckPermsAccess.toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                noLuckPermsAccessMode = NoLuckPermsAccessMode.EVERYONE;
            }

            noLuckPermsAccess = noLuckPermsAccessMode.name();
        }

        private void save() {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, GSON.toJson(this));
            } catch (IOException e) {
                LOGGER.error("[SleepMenu] Failed to write config file: {}", CONFIG_PATH, e);
            }
        }
    }
}