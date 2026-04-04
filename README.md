# SleepMenu

SleepMenu is a server-side Fabric mod for Minecraft.
Players standing on a bed get a clickable chat menu to change time and weather.

Stand on a bed, click your choice, and shape the sky: day, night, clear, rain, or thunder.

If `Mod Menu` and `Cloth Config` are installed on the client, the config can also be edited through an in-game config screen. Dedicated servers do not need either dependency.

[![GitHub Release](https://img.shields.io/github/v/release/SwordfishBE/SleepMenu?display_name=release&logo=github)](https://github.com/SwordfishBE/SleepMenu/releases)
[![GitHub Downloads](https://img.shields.io/github/downloads/SwordfishBE/SleepMenu/total?logo=github)](https://github.com/SwordfishBE/SleepMenu/releases)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/aZy0VkOR?logo=modrinth&logoColor=white&label=Modrinth%20downloads)](https://modrinth.com/mod/sleepmenu)
[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1496098?logo=curseforge&logoColor=white&label=CurseForge%20downloads)](https://www.curseforge.com/minecraft/mc-mods/sleepmenu)

---

## 🎮 Commands

- `/sleepmenu open`
- `/sleepmenu set <option>`
- `/sleepmenu reload` (admin only; console allowed)

Options:

`day`, `midnight`, `night`, `noon`, `clear`, `rain` and `thunder`

---

## 🔨 Server-side

This mod runs fully server-side. Clients do not need to install the mod.
Also works in single-player (without LuckPerms support).

---

## 🔄 LuckPerms permissions

If LuckPerms is installed, these nodes are checked:

- `sleepmenu.use`
- `sleepmenu.time.day`
- `sleepmenu.time.midnight`
- `sleepmenu.time.night`
- `sleepmenu.time.noon`
- `sleepmenu.weather.clear`
- `sleepmenu.weather.rain`
- `sleepmenu.weather.thunder`

If LuckPerms is missing, fallback behavior is controlled by config.

### 🌍 LuckPerms quick start

Example: allow everyone to open the Sleep Menu, but only moderators to change weather.

```text
/lp group default permission set sleepmenu.use true
/lp group moderator permission set sleepmenu.weather.clear true
/lp group moderator permission set sleepmenu.weather.rain true
/lp group moderator permission set sleepmenu.weather.thunder true
```

Official LuckPerms docs:

- https://luckperms.net/wiki/Home
- https://luckperms.net/wiki/Command-Usage

---

## ⚙️ Configuration

Config file: `config/sleepmenu.json`

```json
{
  "cooldownTicks": 400,
  "antiSpamWindowTicks": 12000,
  "timeChangeLimit": 4,
  "weatherChangeLimit": 4,
  "noLuckPermsAccess": "EVERYONE"
}
```

- `cooldownTicks`: minimum ticks between successful changes per player (20 ticks = 1 second).
- `antiSpamWindowTicks`: shared anti-spam window for successful changes across all players. Default `12000` ticks = 10 minutes.
- `timeChangeLimit`: maximum successful time changes allowed inside the anti-spam window. Set `0` to disable.
- `weatherChangeLimit`: maximum successful weather changes allowed inside the anti-spam window. Set `0` to disable.
- `noLuckPermsAccess`: `EVERYONE` or `OP_ONLY`.

---

## 📦 Installation

| Platform   | Link |
|------------|------|
| GitHub     | [Releases](https://github.com/SwordfishBE/SleepMenu/releases) |
| Modrinth   | [SleepMenu](https://modrinth.com/mod/sleepmenu) |
| CurseForge | [SleepMenu](https://www.curseforge.com/minecraft/mc-mods/sleepmenu)

1. Download the latest JAR from your preferred platform above.
2. Place the JAR in your server's `mods/` folder.
3. Make sure [Fabric API](https://modrinth.com/mod/fabric-api) is also installed.
4. Start
5. Minecraft — the config file will be created automatically.

---

## 🧱 Building from Source

```bash
git clone https://github.com/SwordfishBE/SleepMenu/TpWithMe.git
cd SleepMenu
chmod +x gradlew
./gradlew build
# Output: build/libs/sleepmenu-<version>.jar
```

---

## ⁉️ Credits/Idea

Based on the command blocks setup created by TOLoneWolf's: [Sleep Menu System for Hypermine SMP](https://www.reddit.com/r/Hypermine/comments/4yegs2/hypermine_19_bed_sleep_menu_system_one_person/)

---

## 📄 License

Released under the [AGPL-3.0 License](LICENSE).
