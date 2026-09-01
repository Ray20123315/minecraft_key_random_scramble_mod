# Random Keys Survival / 亂鍵生存

Minecraft Java **1.20.1** mod for **Fabric + Forge**.

## Gameplay rules

- Every accepted player damage event changes exactly **one enabled KeyMapping**.
- The replacement is a broad random keyboard key, or **Unbound / 未指定**. Escape is excluded so the pause menu remains reachable.
- Before any mutation or multiplayer layout exchange, held/clicked KeyMapping states are released to prevent stuck movement after a key changes while held.
- Enabled mappings are runtime-locked: changing them in Minecraft Controls (including Reset All) is immediately reverted while connected to the random-keys server.
- The server stores each player's accumulated scrambled layout by UUID. Disconnecting restores the player's local controls for normal use, but reconnecting reapplies the **server-saved scrambled layout before any client snapshot is accepted**, so leaving, editing controls, and rejoining cannot reset the challenge.
- Default enabled/HUD list: forward, left, back, right, jump, sneak, sprint, inventory, swap offhand, drop, player list, pick block.
- Other vanilla or mod-provided KeyMappings are untouched and hidden until explicitly added with `/randomkeys add <translation-key>`.
- Once a mapping is added, it is eligible even if its current binding is a mouse button or Unbound; the next mutation can assign a keyboard key or Unbound.
- Every 3 minutes, current enabled layouts exchange between online players: 1 player=no-op, 2 players=mutual swap, 3+ players=one randomized cycle (no isolated pair swaps and nobody keeps their own layout).
- Creative, Adventure, and Spectator are blocked by default. OPs may run `/!c` to toggle a **temporary maintenance bypass** for themselves; the bypass expires on disconnect. Without the bypass, any non-Survival mode is forced back to Survival even if another command/mod tries to change it.
- `keepInventory` is continuously forced to `false`; it cannot remain enabled.

## Commands

Server commands:

- `/randomkeys list`
- `/randomkeys add <translation-key>` — OP level 2
- `/randomkeys remove <translation-key>` — OP level 2
- `/randomkeys reset` — OP level 2

Client helper:

- `/randomkeysclient available [filter]`

The helper lists registered KeyMapping translation keys, including mappings registered by other mods. Use those IDs with `/randomkeys add ...`.

## Build / Release

GitHub Actions builds both loaders with Java 17. Successful pushes to `main` publish the Fabric and Forge runtime JARs to a GitHub Release.
