# Random Keys Survival / 亂鍵生存 / 乱键生存

Minecraft Java **1.20.1** mod for **Fabric + Forge**.

[繁體中文](#繁體中文) · [简体中文](#简体中文) · [English](#english)

---

## 繁體中文

### 玩法

- 玩家每次實際受到傷害時，只會隨機改變 **1 個已啟用的按鍵功能**。
- 隨機結果只會使用一般 PC 常見鍵、明確允許的小鍵盤數字／運算鍵，以及 `未指定`；不會再抽到 `World 1`、無效 `key.keyboard.xx`、F13-F25、Windows/Super 等不符合一般鍵盤需求的鍵。
- 預設亂鍵／HUD 清單：向前、向左、向後、向右、跳躍、攻擊、放置／使用、潛行、背包、丟棄物品、副手切換、快捷欄 1-9。
- HUD 固定顯示在右上角並分成兩欄：左欄顯示上述核心操作，右欄顯示快捷欄 1-9；之後用指令加入的額外 KeyMapping 會附加顯示。
- HUD 顏色：目前按鍵與進入亂鍵系統前的原始鍵位相同時顯示 **綠色**；不同時顯示 **紅色**。
- 小鍵盤數字在繁體中文 HUD 顯示為 `小鍵盤0`～`小鍵盤9`。
- 其他原版或其他 Mod 註冊的 KeyMapping 預設完全不修改、也不顯示；只有用 `/randomkeys add <translation-key>` 加入後才會被亂鍵並顯示。
- 白名單內的功能即使原本綁定滑鼠按鍵或未指定，也可以在受傷時被改成鍵盤鍵或未指定。
- 每次變更或多人交換前都會先釋放按住／點擊狀態，避免交換後卡住持續移動。
- 連上伺服器後，亂鍵白名單內的 Controls 設定會被鎖定；玩家不能靠設定頁或「重設全部」改回去。
- 伺服器依玩家 UUID 保存累積亂鍵配置。離線時客戶端會恢復自己的正常鍵位，但重新進入同一伺服器時，伺服器保存的亂鍵配置會先套回，不能靠退出後改鍵再進入繞過。
- 每 3 分鐘交換在線玩家目前的整套亂鍵配置：1 人不交換；2 人互換；3 人以上形成一個隨機循環，例如 `A → B → C → A`，不做互相成對交換，也不讓玩家保留自己的配置。
- 當快捷欄鍵位納入亂鍵時，滑鼠滾輪切換快捷欄會被禁止。
- 創造、冒險、旁觀模式預設禁止；OP 可使用 `/!c` 暫時開啟個人維護模式，離線後自動失效。
- `keepInventory`（死亡不掉落）會持續被強制為 `false`，無法保持開啟。
- 支援繁體中文 `zh_tw`、簡體中文 `zh_cn`、英文 `en_us`。

### 指令

- `/randomkeys list`：查看目前亂鍵白名單。
- `/randomkeys add <translation-key>`：OP 等級 2，加入原版／Mod KeyMapping。
- `/randomkeys remove <translation-key>`：OP 等級 2，移除 KeyMapping。
- `/randomkeys reset`：OP 等級 2，重設為預設白名單。
- `/randomkeysclient available [filter]`：列出客戶端已註冊的 KeyMapping translation key，方便加入其他 Mod 鍵位。
- `/!c`：OP 等級 2，切換個人暫時維護模式。

### 安裝與建置

- Fabric 版本需要 Fabric Loader 與 Fabric API。
- Forge 版本使用 Minecraft 1.20.1 / Forge 47.x。
- Java 版本：17。
- GitHub Actions 會同時建置 Fabric 與 Forge；`main` 建置成功後會把兩個 runtime JAR 發佈到 GitHub Releases。

### 許可證

- Copyright © 2026 **Ray20123315**。
- **All Rights Reserved**。
- 完整條款請見根目錄 [`LICENSE`](LICENSE)。

---

## 简体中文

### 玩法

- 玩家每次实际受到伤害时，只会随机改变 **1 个已启用的按键功能**。
- 随机结果只会使用常见 PC 键、明确允许的小键盘数字／运算键，以及 `未指定`；不会再抽到 `World 1`、无效 `key.keyboard.xx`、F13-F25、Windows/Super 等不符合普通键盘需求的键。
- 默认乱键／HUD 列表：向前、向左、向后、向右、跳跃、攻击、放置／使用、潜行、背包、丢弃物品、副手切换、快捷栏 1-9。
- HUD 固定显示在右上角并分成两栏：左栏显示上述核心操作，右栏显示快捷栏 1-9；之后用命令加入的额外 KeyMapping 会附加显示。
- HUD 颜色：当前按键与进入乱键系统前的原始键位相同时显示 **绿色**；不同时显示 **红色**。
- 小键盘数字在简体中文 HUD 显示为 `小键盘0`～`小键盘9`。
- 其他原版或其他 Mod 注册的 KeyMapping 默认完全不修改、也不显示；只有用 `/randomkeys add <translation-key>` 加入后才会被乱键并显示。
- 白名单内的功能即使原本绑定鼠标按键或未指定，也可以在受伤时被改成键盘键或未指定。
- 每次变更或多人交换前都会先释放按住／点击状态，避免交换后卡住持续移动。
- 连接服务器后，乱键白名单内的 Controls 设置会被锁定；玩家不能通过设置页面或“重置全部”改回去。
- 服务器按玩家 UUID 保存累积乱键配置。离线时客户端会恢复自己的正常键位，但重新进入同一服务器时，会先套回服务器保存的乱键配置，不能通过退出后改键再进入来绕过。
- 每 3 分钟交换在线玩家当前的整套乱键配置：1 人不交换；2 人互换；3 人以上形成一个随机循环，例如 `A → B → C → A`，不进行成对互换，也不会让玩家保留自己的配置。
- 当快捷栏键位纳入乱键时，鼠标滚轮切换快捷栏会被禁止。
- 创造、冒险、旁观模式默认禁止；OP 可使用 `/!c` 暂时开启个人维护模式，离线后自动失效。
- `keepInventory`（死亡不掉落）会持续被强制为 `false`，无法保持开启。
- 支持繁体中文 `zh_tw`、简体中文 `zh_cn`、英文 `en_us`。

### 命令

- `/randomkeys list`：查看当前乱键白名单。
- `/randomkeys add <translation-key>`：OP 等级 2，加入原版／Mod KeyMapping。
- `/randomkeys remove <translation-key>`：OP 等级 2，移除 KeyMapping。
- `/randomkeys reset`：OP 等级 2，重置为默认白名单。
- `/randomkeysclient available [filter]`：列出客户端已注册的 KeyMapping translation key，方便加入其他 Mod 键位。
- `/!c`：OP 等级 2，切换个人临时维护模式。

### 安装与构建

- Fabric 版本需要 Fabric Loader 与 Fabric API。
- Forge 版本使用 Minecraft 1.20.1 / Forge 47.x。
- Java 版本：17。
- GitHub Actions 会同时构建 Fabric 与 Forge；`main` 构建成功后会把两个 runtime JAR 发布到 GitHub Releases。

### 许可证

- Copyright © 2026 **Ray20123315**。
- **All Rights Reserved**。
- 完整条款请见根目录 [`LICENSE`](LICENSE)。

---

## English

### Gameplay

- Every accepted player damage event changes exactly **one enabled control**.
- Random results are restricted to common PC keyboard keys, explicitly allowed numpad digits/operators, and `Unbound`. Uncommon or invalid values such as `World 1`, unnamed `key.keyboard.xx` gaps, F13-F25, and Windows/Super keys are excluded.
- Default scramble/HUD list: forward, left, back, right, jump, attack, use/place, sneak, inventory, drop, swap offhand, and hotbar 1-9.
- The HUD is anchored at the upper-right and split into two columns: core controls on the left, hotbar 1-9 on the right. Extra KeyMappings added by command are appended to the HUD.
- HUD colors: **green** means the current binding matches the original binding captured before scrambling; **red** means it differs.
- Numpad digits are shown as `Numpad 0` through `Numpad 9` in English.
- Other vanilla or mod-provided KeyMappings are untouched and hidden by default. They are only scrambled and shown after `/randomkeys add <translation-key>`.
- An enabled mapping remains eligible even if its original binding is a mouse button or Unbound; damage may reassign it to a keyboard key or Unbound.
- Held/clicked states are released before mutations and multiplayer exchanges to prevent stuck movement after a binding changes while held.
- Enabled mappings are runtime-locked while connected. Changes made in Controls, including Reset All, are immediately reverted.
- The server persists each player's accumulated scrambled layout by UUID. Disconnecting restores normal local controls, but reconnecting reapplies the server-saved scrambled layout before a client snapshot is accepted, preventing leave/edit/rejoin bypasses.
- Every 3 minutes, online players exchange their complete current layouts: 1 player = no-op; 2 players = mutual swap; 3+ players = one randomized cycle such as `A → B → C → A`, with no isolated pair swaps and nobody retaining their own layout.
- Mouse-wheel hotbar switching is blocked while hotbar mappings are managed by the mod.
- Creative, Adventure, and Spectator are blocked by default. OPs can use `/!c` for a temporary personal maintenance bypass; it expires on disconnect.
- `keepInventory` is continuously forced to `false`.
- Localized for Traditional Chinese `zh_tw`, Simplified Chinese `zh_cn`, and English `en_us`.

### Commands

- `/randomkeys list` — show the current scramble whitelist.
- `/randomkeys add <translation-key>` — OP level 2; add a vanilla/mod KeyMapping.
- `/randomkeys remove <translation-key>` — OP level 2; remove a KeyMapping.
- `/randomkeys reset` — OP level 2; restore the default whitelist.
- `/randomkeysclient available [filter]` — list registered client KeyMapping translation keys, including keys from other mods.
- `/!c` — OP level 2; toggle the personal temporary maintenance bypass.

### Install and build

- Fabric build requires Fabric Loader and Fabric API.
- Forge build targets Minecraft 1.20.1 / Forge 47.x.
- Java: 17.
- GitHub Actions builds both loaders; successful `main` builds publish both runtime JARs to GitHub Releases.

### License

- Copyright © 2026 **Ray20123315**.
- **All Rights Reserved**.
- See the root [`LICENSE`](LICENSE) file for the complete terms.
