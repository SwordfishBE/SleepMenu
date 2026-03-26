# SleepMenu

SleepMenu is a server-side Fabric mod for Minecraft.
Players standing on a bed get a clickable chat menu to change time and weather.

---

## 🎮 Commands

- `/sleepmenu open`
- `/sleepmenu set <option>`
- `/sleepmenu reload` (admin only; console allowed)

Options:

- `day`
- `midnight`
- `night`
- `noon`
- `clear`
- `rain`
- `thunder`

---

## 🔨 Server-side only

This mod runs fully server-side. Clients do not need to install the mod.

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
  "noLuckPermsAccess": "EVERYONE"
}
```

- `cooldownTicks`: minimum ticks between successful changes per player (20 ticks = 1 second).
- `noLuckPermsAccess`: `EVERYONE` or `OP_ONLY`.

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in `mods/`.
3. Download `sleepmenu-<version>.jar` and place it in `mods/`.
4. Launch Minecraft. The config is created automatically on first run.

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

## 📄 License

Released under the [AGPL-3.0 License](LICENSE).
