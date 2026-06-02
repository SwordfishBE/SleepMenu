package net.sleepmenu;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class SleepMenuClothConfigScreen {
    private SleepMenuClothConfigScreen() {
    }

    static Screen create(Screen parent) {
        if (isConnectedToRemoteServer()) {
            return new AlertScreen(
                () -> Minecraft.getInstance().setScreenAndShow(parent),
                Component.literal("Sleep Menu Config"),
                Component.literal("Sleep Menu uses the server config. While connected to a remote server, Mod Menu cannot edit it. Edit the server config directly, then run /sleepmenu reload.")
            );
        }

        SleepMenuMod.SleepMenuConfig config = SleepMenuMod.loadConfigForEditing();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.literal("Sleep Menu Config"))
            .setSavingRunnable(() -> SleepMenuMod.applyEditedConfig(config));

        ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
        ConfigEntryBuilder entries = builder.entryBuilder();

        general.addEntry(entries.startIntField(Component.literal("Cooldown Ticks"), config.cooldownTicks)
            .setDefaultValue(400)
            .setMin(0)
            .setTooltip(Component.literal("Minimum ticks between successful changes for the same player."))
            .setSaveConsumer(value -> config.cooldownTicks = value)
            .build());

        general.addEntry(entries.startIntField(Component.literal("Anti-Spam Window Ticks"), config.antiSpamWindowTicks)
            .setDefaultValue(12000)
            .setMin(0)
            .setTooltip(Component.literal("Shared anti-spam window across all players. 12000 ticks = 10 minutes."))
            .setSaveConsumer(value -> config.antiSpamWindowTicks = value)
            .build());

        general.addEntry(entries.startIntField(Component.literal("Time Change Limit"), config.timeChangeLimit)
            .setDefaultValue(4)
            .setMin(0)
            .setTooltip(Component.literal("Maximum successful time changes allowed inside the anti-spam window."))
            .setSaveConsumer(value -> config.timeChangeLimit = value)
            .build());

        general.addEntry(entries.startIntField(Component.literal("Weather Change Limit"), config.weatherChangeLimit)
            .setDefaultValue(4)
            .setMin(0)
            .setTooltip(Component.literal("Maximum successful weather changes allowed inside the anti-spam window."))
            .setSaveConsumer(value -> config.weatherChangeLimit = value)
            .build());

        general.addEntry(entries.startEnumSelector(
                Component.literal("No LuckPerms Access"),
                SleepMenuMod.NoLuckPermsAccessMode.class,
                config.noLuckPermsAccessMode
            )
            .setDefaultValue(SleepMenuMod.NoLuckPermsAccessMode.EVERYONE)
            .setTooltip(Component.literal("Fallback access mode when LuckPerms is not installed."))
            .setEnumNameProvider(mode -> Component.literal(mode.name()))
            .setSaveConsumer(value -> {
                config.noLuckPermsAccessMode = value;
                config.noLuckPermsAccess = value.name();
            })
            .build());

        return builder.build();
    }

    private static boolean isConnectedToRemoteServer() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getConnection() != null && !minecraft.hasSingleplayerServer();
    }
}
