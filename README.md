# Random Keys Survival / 亂鍵生存

Minecraft Java 1.20.1 mod for Fabric and Forge.

## Rules

- Every accepted damage event changes exactly **one** enabled keyboard KeyMapping to a different random keyboard key.
- Default enabled/HUD list: forward, back, left, right, jump, sneak, sprint, inventory, swap offhand, drop, player list, pick block.
- Other vanilla or mod-provided KeyMappings are untouched and hidden until added with `/randomkeys add <translation-key>`.
- Mouse bindings are displayed when enabled but are not scrambled.
- Every 3 minutes, current enabled layouts exchange between online players: 1=no-op, 2=swap, 3+=one randomized cycle.

## Commands

Server commands (operators for mutating configuration):

- `/randomkeys add <translation-key>`
- `/randomkeys remove <translation-key>`
- `/randomkeys list`
- `/randomkeys reset`

Client helper:

- `/randomkeysclient available [filter]`

The client helper lists registered KeyMapping translation keys, including keys registered by other mods.

## Build

GitHub Actions builds both loaders. Successful pushes to `main` publish the two runtime JARs into a GitHub Release.
