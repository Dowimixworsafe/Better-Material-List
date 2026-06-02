# Better List for Litematica

A **Litematica add-on** for Fabric that turns the material list into a full build-tracking tool.

Track what you've placed, what's stored in your chests, and what each teammate is working on — all from one upgraded material list. Works in singleplayer and on servers, solo or in a party.

> Requires [Litematica](https://modrinth.com/mod/litematica) and [MaLiLib](https://modrinth.com/mod/malilib) (plus Fabric API). Client-side.

## Features

- **Material checklist** — tick off items per schematic as you gather them; state is saved **per server**.
- **Tracked chests → live "stored" counts** — mark any chest as storage and its contents are counted into a *Stored* column, so you instantly see what you still need vs. what's already in bulk storage.
  - 🔍 **Preview** a tracked chest's remembered contents without walking to it.
  - 💡 **Highlight** tracked chests in the world — visible **through walls**.
- **Targeted-items HUD** — right-click items to *target* them, then toggle a compact top-right HUD showing `have / need`. Items drop off the HUD automatically once you've gathered enough. Works **solo**.
- **Party sync** — invite players, share schematic placements, and sync checklist + stored progress between everyone. See which teammates are focusing which items (colored borders + player heads on the list).
- **Filtering & sorting** — search, hide fully-placed / fully-stored / checked items, sort by any column, filter the list to a specific player's targets.
- **Localization** — English (default) and Polish; switch in *Options → Language*.

## Default keybinds

| Key | Action |
|-----|--------|
| `.` | Open material list |
| `R` | Reload list (inside the GUI) |
| `K` | Open tracked-chests screen |
| `H` | Toggle chest highlighting |
| `J` | Toggle targeted-items HUD |
| `O` | Open party screen |
| `,` | Open config |

All keybinds are rebindable in the config screen.

## Party sync — server setup

All single-player features work without any server setup. **Party sync** additionally needs the server to relay BML packets. Two options:

- **Fabric server:** drop this same mod into the server's `mods/` folder (with Fabric API). No extra download.
- **Bukkit-family server** (Paper, Purpur, Spigot, …): install the companion **[BML Integration plugin](https://github.com/Dowimixworsafe/Better-Material-List-Sync-Plugin)**.

Either way, party members just need the **same mod version** on their clients.

## Building

Requires the Java 25 toolchain.

```bash
./gradlew build      # → build/libs/betterlist-<version>.jar
./gradlew runClient  # launch a dev client
```

## License

All Rights Reserved. Litematica and MaLiLib are separate projects (LGPL-3.0) and are required as runtime dependencies — they are not bundled with this mod.
