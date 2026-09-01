# Random Keys Survival / 亂鍵生存

Minecraft Java **1.20.1** mod for **Fabric + Forge**.

## Gameplay rules

- Every accepted player damage event changes exactly **one enabled control**.
- The replacement is a broad random keyboard key (including **Numpad 0-9 and keypad operator keys whether or not the physical keyboard has a numpad**) or **Unbound / 未指定**. Escape is excluded so the pause menu remains reachable.
- Before any mutation or multiplayer layout exchange, held/clicked KeyMapping states are released to prevent stuck movement after a key changes while held.
- Enabled mappings are runtime-locked: changing them in Minecraft Controls (including Reset All) is immediately reverted while connected to the random-keys server.
- The server stores each player's accumulated scrambled layout by UUID. Disconnecting restores local controls for normal use, but reconnecting reapplies the **server-saved scrambled layout before any client snapshot is accepted**, preventing leave/edit/rejoin bypass.
- Default enabled/HUD list matches the reference layout: forward, left, back, right, jump, attack, use/place, sneak, inventory, drop, swap offhand, and hotbar 1-9.
- Other vanilla or mod-provided KeyMappings are untouched and hidden until explicitly added with `/randomkeys add <translation-key>`.
- Once a mapping is added, it is eligible even if its current binding is a mouse button or Unbound; the next mutation can assign a keyboard key or Unbound.
- HUD renders only enabled controls. Numpad digit bindings are rendered as `小鍵盤0`…`小鍵盤9` in Traditional Chinese, `小键盘0`…`小键盘9` in Simplified Chinese, and `Numpad 0`…`Numpad 9` in English.
- Mouse-wheel hotbar switching is disabled whenever hotbar KeyMappings are enabled; hotbar slots must then be selected through their randomized key mappings.
- Every 3 minutes, current enabled layouts exchange between online players: 1 player=no-op, 2 players=mutual swap, 3+ players=one randomized cycle (no isolated pair swaps and nobody keeps their own layout).
- Creative, Adventure, and Spectator are blocked by default. OPs may run `/!c` to toggle a **temporary maintenance bypass** for themselves; the bypass expires on disconnect. Without bypass, any non-Survival mode is forced back to Survival even if another command/mod tries to change it.
- `keepInventory` is continuously forced to `false`; it cannot remain enabled.
- Mod messages are localized for **繁體中文 (`zh_tw`) / 简体中文 (`zh_cn`) / English (`en_us`)**.

## Commands

Server commands:

- `/randomkeys list`
- `/randomkeys add <translation-key>` — OP level 2
- `/randomkeys remove <translation-key>` — OP level 2
- `/randomkeys reset` — OP level 2
- `/!c` — OP level 2, toggles the temporary Creative/Adventure/Spectator maintenance bypass

Client helper:

- `/randomkeysclient available [filter]`

The helper lists registered KeyMapping translation keys, including mappings registered by other mods. Use those IDs with `/randomkeys add ...`.

## Build / Release

GitHub Actions builds both loaders with Java 17. Successful pushes to `main` publish the Fabric and Forge runtime JARs to a GitHub Release.
