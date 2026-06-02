# Better List for Litematica

**A Litematica add-on that turns the material list into a full build-tracking tool.**

Track what you've placed, what's stored in your chests, and what each teammate is working on — all from one upgraded material list. Works in singleplayer and on servers, solo or in a party.

> ⚠️ **Requires [Litematica](https://modrinth.com/mod/litematica) and [MaLiLib](https://modrinth.com/mod/malilib)** (plus Fabric API). Client-side.

---

## ✨ Features

### ✅ Material checklist
Tick off items per schematic as you gather them. State is saved **per server**, so your progress is right where you left it next time you log in.

### 📦 Tracked chests → live "stored" counts
Mark any chest as storage and its contents are counted into a **Stored** column on the list — so you instantly see how much you still need to craft vs. what's already in your bulk storage.
- 🔍 **Preview** a chest's contents without walking to it
- 💡 **Highlight** tracked chests in the world — visible **through walls**

### 🎯 Targeted-items HUD
Right-click items on the list to **target** them, then toggle a compact top-right HUD showing `have / need`. Items drop off the HUD automatically once you've gathered enough. Works **solo** — no party required.

### 👥 Party sync
Build together: invite players, share schematic placements, and sync checklist + stored progress between everyone. See which teammates are focusing which items (colored borders + player heads on the list).

### 🔎 Powerful filtering & sorting
- Search by item name
- Hide fully-placed / fully-stored / checked items
- Sort by any column (Need, Done, Have, Miss, …)
- Filter the list to a specific player's targets

### 🌍 Localization
Fully translated — **English** (default) and **Polish**. Switch in *Options → Language*.

---

## ⌨️ Default keybinds

| Key | Action |
|:---:|--------|
| `.` | Open material list |
| `R` | Reload list (inside the GUI) |
| `K` | Open tracked-chests screen |
| `H` | Toggle chest highlighting |
| `J` | Toggle targeted-items HUD |
| `O` | Open party screen |
| `,` | Open config |

All keybinds are rebindable in the config screen.

---

## 📥 Requirements

- **Fabric Loader** 0.18.2+
- **Fabric API**
- **MaLiLib**
- **Litematica**
- Minecraft **26.1.2**

Party sync works through a server-side relay (a Paper/Bukkit plugin or the built-in Fabric server path); without it, all single-player features still work normally.

---

## 💬 Notes

Litematica and MaLiLib are separate projects by *masa* and are required as dependencies — they are **not** bundled with this mod. Big thanks to them for the foundation this add-on builds on.

Found a bug or have a suggestion? Open an issue on [GitHub](https://github.com/Dowimixworsafe/Better-Material-List/issues).
