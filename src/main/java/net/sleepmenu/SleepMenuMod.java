package net.sleepmenu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

public class SleepMenuMod implements ModInitializer {
    public static final String MOD_ID = "sleepmenu";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String MODRINTH_PROJECT_ID = "aZy0VkOR";

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
        UpdateChecker.checkForUpdatesAsync();

        logInfo("Initialized");
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("sleepmenu")
                .then(Commands.literal("reload").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    if (!isAdminSource(source)) {
                        source.sendFailure(Component.literal("You do not have permission to reload Sleep Menu."));
                        return 0;
                    }

                    reloadConfig();
                    source.sendSuccess(() -> Component.literal("[SleepMenu] Config reloaded."), false);
                    return 1;
                }))
                .then(Commands.literal("open").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    return openMenuForPlayer(player, true) ? 1 : 0;
                }))
                .then(Commands.literal("set")
                    .then(Commands.argument("option", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (MenuAction action : MENU_ACTIONS) {
                                builder.suggest(action.id);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> executeSetCommand(context.getSource(), StringArgumentType.getString(context, "option")))))
        ));
    }

    private int executeSetCommand(CommandSourceStack source, String rawOption) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        MenuAction action = ACTIONS_BY_ID.get(rawOption.toLowerCase(Locale.ROOT));
        if (action == null) {
            source.sendFailure(Component.literal("Unknown sleep menu option."));
            return 0;
        }

        return executeAction(source, player, action, true) ? 1 : 0;
    }

    private boolean isAdminSource(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }

        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private void reloadConfig() {
        this.config = SleepMenuConfig.load();
        this.permissionService = new PermissionService(this.config);
        logInfo("Config reloaded");
    }

    private void onServerTick(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
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
                player.sendOverlayMessage(Component.empty());
            }

            if (!onBed) {
                continue;
            }

            if (!permissionService.hasPermission(player, PERM_USE)) {
                if (state.hintTicker % 40 == 0) {
                    player.sendOverlayMessage(Component.literal("You do not have permission to use Sleep Menu."));
                }
                state.hintTicker++;
                continue;
            }

            if (state.hintTicker % 40 == 0) {
                player.sendOverlayMessage(Component.literal("Sleep Menu: click chat buttons, or use /sleepmenu open"));
            }
            state.hintTicker++;
        }

        states.keySet().retainAll(online);
        lastActionTickByPlayer.keySet().retainAll(online);
    }

    private boolean openMenuForPlayer(ServerPlayer player, boolean fromCommand) {
        if (!isStandingOnBed(player)) {
            if (fromCommand) {
                player.sendSystemMessage(Component.literal("Stand on a bed to use Sleep Menu."));
            }
            return false;
        }

        if (!permissionService.hasPermission(player, PERM_USE)) {
            player.sendSystemMessage(Component.literal("You do not have permission to use Sleep Menu."));
            return false;
        }

        player.sendSystemMessage(Component.literal("[SleepMenu] Choose an option:").withStyle(ChatFormatting.LIGHT_PURPLE));
        player.sendSystemMessage(buildClickableRow("Time", List.of("day", "midnight", "night", "noon")));
        player.sendSystemMessage(buildClickableRow("Weather", List.of("clear", "rain", "thunder")));
        return true;
    }

    private MutableComponent buildClickableRow(String title, List<String> ids) {
        MutableComponent row = Component.literal(title + ": ").withStyle(ChatFormatting.GOLD);

        for (int i = 0; i < ids.size(); i++) {
            MenuAction action = ACTIONS_BY_ID.get(ids.get(i));
            if (action == null) {
                continue;
            }

            row.append(Component.literal("[" + action.label + "]")
                .withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.RunCommand("/sleepmenu set " + action.id))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to apply: " + action.label)))));

            if (i < ids.size() - 1) {
                row.append(Component.literal(" ").withStyle(ChatFormatting.GRAY));
            }
        }

        return row;
    }

    private boolean executeAction(CommandSourceStack source, ServerPlayer player, MenuAction action, boolean fromDirectSet) {
        MinecraftServer server = source.getServer();
        if (!isStandingOnBed(player)) {
            player.sendOverlayMessage(Component.literal("Stand on a bed to use Sleep Menu."));
            return false;
        }

        if (!permissionService.hasPermission(player, PERM_USE)) {
            player.sendOverlayMessage(Component.literal("You do not have permission to use Sleep Menu."));
            return false;
        }

        if (!permissionService.hasPermission(player, action.permissionNode)) {
            player.sendOverlayMessage(Component.literal("You do not have permission for this option."));
            return false;
        }

        long nowTick = getCurrentServerTick(server);
        long lastTick = lastActionTickByPlayer.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
        long elapsed = nowTick - lastTick;
        if (elapsed < config.cooldownTicks) {
            long remaining = config.cooldownTicks - elapsed;
            player.sendOverlayMessage(Component.literal("Sleep Menu cooldown: " + remaining + " ticks left."));
            return false;
        }

        boolean applied = switch (action.type) {
            case TIME -> setTime(server, action.targetTime);
            case WEATHER -> setWeather(server, action.raining, action.thundering);
        };

        if (!applied) {
            player.sendOverlayMessage(Component.literal("Could not apply Sleep Menu action right now."));
            return false;
        }

        lastActionTickByPlayer.put(player.getUUID(), nowTick);
        broadcastAction(server, player, action.broadcastMessage);

        if (fromDirectSet) {
            player.sendOverlayMessage(Component.literal("Applied: " + action.label));
        }

        return true;
    }

    private boolean setTime(MinecraftServer server, long targetTime) {
        server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS).withSuppressedOutput(),
            "time set " + targetTime
        );
        return true;
    }

    private boolean setWeather(MinecraftServer server, boolean raining, boolean thundering) {
        String weatherCommand;
        if (!raining && !thundering) {
            weatherCommand = "weather clear";
        } else if (thundering) {
            weatherCommand = "weather thunder";
        } else {
            weatherCommand = "weather rain";
        }

        server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS).withSuppressedOutput(),
            weatherCommand
        );
        return true;
    }

    private long getCurrentServerTick(MinecraftServer server) {
        return server.getTickCount();
    }

    private void broadcastAction(MinecraftServer server, ServerPlayer actor, String action) {
        Component message = Component.literal(actor.getName().getString() + ": " + action);
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private boolean isStandingOnBed(ServerPlayer player) {
        BlockState feet = player.level().getBlockState(player.blockPosition());
        if (feet.is(BlockTags.BEDS)) {
            return true;
        }

        BlockState below = player.level().getBlockState(player.blockPosition().below());
        return below.is(BlockTags.BEDS);
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
        private final SleepMenuConfig config;

        private PermissionService(SleepMenuConfig config) {
            this.config = config;
            if (FabricLoader.getInstance().isModLoaded("luckperms")) {
                logInfo("LuckPerms detected, permission nodes are active.");
            } else {
                logInfo("LuckPerms not found, fallback mode: " + config.noLuckPermsAccessMode);
            }
        }

        private boolean hasPermission(ServerPlayer player, String node) {
            return me.lucko.fabric.api.permissions.v0.Permissions.check(
                player,
                node,
                config.noLuckPermsAccessMode == NoLuckPermsAccessMode.EVERYONE
            );
        }
    }

    private static final class UpdateChecker {
        private static final Gson GSON = new Gson();
        private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
        private static final String CURRENT_VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("0.0.0");

        private static void checkForUpdatesAsync() {
            CompletableFuture.runAsync(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT_ID + "/version"))
                        .header("User-Agent", "SwordfishBE/SleepMenu/" + CURRENT_VERSION)
                        .GET()
                        .build();

                    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        LOGGER.warn("[SleepMenu] Modrinth update check failed with status {}.", response.statusCode());
                        return;
                    }

                    String newestVersion = findNewestPublishedVersion(response.body());
                    if (newestVersion == null) {
                        return;
                    }

                    if (compareVersions(newestVersion, CURRENT_VERSION) > 0) {
                        LOGGER.warn("[SleepMenu] Update available: {} -> {}", CURRENT_VERSION, newestVersion);
                        LOGGER.warn("[SleepMenu] Download: https://modrinth.com/project/{}/versions", MODRINTH_PROJECT_ID);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[SleepMenu] Modrinth update check failed.", e);
                }
            });
        }

        private static String findNewestPublishedVersion(String responseBody) {
            JsonElement root = GSON.fromJson(responseBody, JsonElement.class);
            if (!(root instanceof JsonArray versions)) {
                return null;
            }

            String newestVersion = null;
            for (JsonElement element : versions) {
                if (!(element instanceof JsonObject versionObject)) {
                    continue;
                }

                String versionType = getString(versionObject, "version_type");
                if ("alpha".equalsIgnoreCase(versionType) || "beta".equalsIgnoreCase(versionType)) {
                    continue;
                }

                String versionNumber = getString(versionObject, "version_number");
                if (versionNumber == null || versionNumber.isBlank()) {
                    continue;
                }

                if (newestVersion == null || compareVersions(versionNumber, newestVersion) > 0) {
                    newestVersion = versionNumber;
                }
            }

            return newestVersion;
        }

        private static String getString(JsonObject object, String key) {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull()) {
                return null;
            }
            return element.getAsString();
        }

        private static int compareVersions(String left, String right) {
            String[] leftParts = normalizeVersion(left).split("\\.");
            String[] rightParts = normalizeVersion(right).split("\\.");
            int maxLength = Math.max(leftParts.length, rightParts.length);

            for (int i = 0; i < maxLength; i++) {
                int leftValue = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
                int rightValue = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
                int comparison = Integer.compare(leftValue, rightValue);
                if (comparison != 0) {
                    return comparison;
                }
            }

            return 0;
        }

        private static String normalizeVersion(String version) {
            return version.toLowerCase(Locale.ROOT)
                .replace("release-", "")
                .replace("version-", "")
                .replaceFirst("^v", "")
                .replaceAll("[^0-9.]", ".");
        }

        private static int parseVersionPart(String value) {
            if (value == null || value.isBlank()) {
                return 0;
            }

            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return 0;
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
                    String json = stripJsonComments(raw);
                    config = GSON.fromJson(json, SleepMenuConfig.class);
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
                Files.writeString(CONFIG_PATH, toCommentedJson(this));
            } catch (IOException e) {
                LOGGER.error("[SleepMenu] Failed to write config file: {}", CONFIG_PATH, e);
            }
        }

        private static String toCommentedJson(SleepMenuConfig config) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            appendComment(sb, "Minimum ticks between successful Sleep Menu actions for the same player.");
            appendComment(sb, "20 ticks = 1 second, so the default 400 ticks equals 20 seconds.");
            appendProperty(sb, "cooldownTicks", config.cooldownTicks, true);

            appendComment(sb, "Fallback access mode when LuckPerms is not installed on the server.");
            appendComment(sb, "Use EVERYONE to allow all players, or OP_ONLY to restrict access to operators.");
            appendProperty(sb, "noLuckPermsAccess", config.noLuckPermsAccess, false);
            sb.append("}\n");
            return sb.toString();
        }

        private static void appendComment(StringBuilder sb, String comment) {
            sb.append("  // ").append(comment).append('\n');
        }

        private static void appendProperty(StringBuilder sb, String key, int value, boolean trailingComma) {
            sb.append("  \"").append(key).append("\": ").append(value);
            if (trailingComma) {
                sb.append(',');
            }
            sb.append('\n').append('\n');
        }

        private static void appendProperty(StringBuilder sb, String key, String value, boolean trailingComma) {
            sb.append("  \"").append(key).append("\": ").append(GSON.toJson(value));
            if (trailingComma) {
                sb.append(',');
            }
            sb.append('\n').append('\n');
        }

        private static String stripJsonComments(String input) {
            StringBuilder sb = new StringBuilder(input.length());
            boolean inString = false;
            boolean escaping = false;
            boolean inLineComment = false;
            boolean inBlockComment = false;

            for (int i = 0; i < input.length(); i++) {
                char current = input.charAt(i);
                char next = i + 1 < input.length() ? input.charAt(i + 1) : '\0';

                if (inLineComment) {
                    if (current == '\n' || current == '\r') {
                        inLineComment = false;
                        sb.append(current);
                    }
                    continue;
                }

                if (inBlockComment) {
                    if (current == '*' && next == '/') {
                        inBlockComment = false;
                        i++;
                    }
                    continue;
                }

                if (inString) {
                    sb.append(current);
                    if (escaping) {
                        escaping = false;
                    } else if (current == '\\') {
                        escaping = true;
                    } else if (current == '"') {
                        inString = false;
                    }
                    continue;
                }

                if (current == '"') {
                    inString = true;
                    sb.append(current);
                    continue;
                }

                if (current == '/' && next == '/') {
                    inLineComment = true;
                    i++;
                    continue;
                }

                if (current == '/' && next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }

                sb.append(current);
            }

            return sb.toString();
        }
    }
}
