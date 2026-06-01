# BML — Better Material List

A **Litematica add-on** for Fabric that turns the material list into a full build-tracking tool.

> Requires [Litematica](https://modrinth.com/mod/litematica) and [MaLiLib](https://modrinth.com/mod/malilib). Client-side; works in singleplayer and on servers.

## Features

- **Material checklist** — tick off items per schematic; state is saved per server.
- **Tracked chests** — mark chests as storage; their contents count toward a "stored" total on the list.
  - 🔍 **Preview** a tracked chest's remembered contents without walking to it.
  - 💡 **Highlight** tracked chests in the world (visible through walls).
- **Targeted-items HUD** — right-click items to target them; a compact top-right HUD shows `have / need` and drops items once you've gathered enough.
- **Party sync** — invite players, share schematic placements, and sync checklist + stored progress (via a server plugin or the built-in Fabric server relay).
- **Filtering & sorting** — search, hide fully-placed/stored/checked, sort by any column, filter the list to a specific player's targets.
- Configurable **keybinds** for the list, chests, config, party, highlight toggle, and HUD.

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

## Building

Requires the Java 25 toolchain.

```bash
./gradlew build      # → build/libs/bettermateriallist-<version>.jar
./gradlew runClient  # launch a dev client
```

## License

All Rights Reserved. Litematica and MaLiLib are separate projects (LGPL-3.0) and are required as runtime dependencies — they are not bundled with this mod.
