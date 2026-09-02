# Random Keys Survival / 亂鍵生存 / 乱键生存

Minecraft Java **1.20.1** mod for **Fabric + Forge**.

[繁體中文](#繁體中文) · [简体中文](#简体中文) · [English](#english)

---

## 繁體中文

### 玩法

- 只有 Minecraft 正常傷害流程讓玩家的**一般 Health（生命值）實際下降**時，才進行一次亂鍵抽選；盾牌完整擋住、只消耗 Absorption（吸收生命）而一般 Health 沒下降時不觸發。致死的最後一次 Health 下降仍會觸發並保存結果。
- 每次有效扣血會隨機選取 **1 個已啟用的按鍵功能**，再做一次綁定抽選；抽到與目前 binding 完全相同是合法結果，因此這次可能沒有可見變化，不會重抽。
- Server 產生的新綁定只會從確認過的常見 PC 鍵與允許的小鍵盤數字／運算鍵中抽取；每個安全鍵各佔 1 份，`未指定（Unbound）` 另佔 1 份，**所有結果完全等機率**。`World 1/2`、無效 `key.keyboard.xx`、F13-F25、Windows/Super 等不會被抽到。
- 預設亂鍵／HUD 清單：向前、向左、向後、向右、跳躍、攻擊、放置／使用、潛行、背包、丟棄物品、副手切換、快捷欄 1-9。
- HUD 固定顯示在右上角並分成兩欄：左欄顯示上述核心操作，右欄顯示快捷欄 1-9；之後用指令加入的額外 KeyMapping 會附加顯示。
- HUD 顏色：目前按鍵與進入亂鍵系統前的原始鍵位相同時顯示 **綠色**；不同時顯示 **紅色**。
- 小鍵盤數字在繁體中文 HUD 顯示為 `小鍵盤0`～`小鍵盤9`。
- 其他原版或其他 Mod 註冊的 KeyMapping 預設完全不修改、也不顯示；只有用 `/randomkeys add <translation-key>` 加入後才會被亂鍵並顯示。
- 白名單內的功能即使原本綁定滑鼠按鍵、scancode 或未指定，也可作為該玩家第一次初始化的原始值；安全鍵池限制只套用 Server 後續隨機產生的新值。
- 每次實際變更或多人交換前都會先釋放按住／點擊狀態，避免交換後卡住持續移動。
- 連上伺服器後，亂鍵白名單內的 Controls 設定會被鎖定；玩家不能靠設定頁或「重設全部」改回去。
- **Server 世界資料是 Layout 的唯一持久權威。** Client Snapshot 只可以補上該玩家在 Server 尚未存在的白名單鍵；Server 一旦已有該鍵值，Client 後續回報無法覆寫。
- 白名單、玩家 UUID→Layout 與交換剩餘 tick 都保存在該 Minecraft save 的世界資料中；同一 save 的主世界、地獄、終界共用同一份資料。舊的 `config/random-keys-survival.json` 不會被新版玩法讀取、寫入、刪除或自動匯入。
- 離線時客戶端會恢復自己的正常鍵位；重新進入同一世界時，Server 已保存的 Layout 會先套回。死亡／重生同樣不會清除已保存的亂鍵 Layout。
- 每 3 分鐘（3600 server ticks）交換在線且 Layout 完整玩家目前的整套亂鍵配置：1 人／不足 2 名合格玩家不交換；2 人互換；3 人以上形成一個隨機循環，例如 `A → B → C → A`。同一 save 中位於不同維度的玩家仍一起參與。
- 交換倒數本身也會寫入世界資料；Server 正常重開後會從先前剩餘 tick 繼續，Server 關閉期間不消耗倒數。
- 當快捷欄鍵位納入亂鍵時，滑鼠滾輪切換快捷欄會被禁止。
- 創造、冒險、旁觀模式預設禁止；OP 可使用 `/!c` 暫時開啟個人維護模式，離線後自動失效。
- `keepInventory`（死亡不掉落）會持續被強制為 `false`，無法保持開啟。
- 支援繁體中文 `zh_tw`、簡體中文 `zh_cn`、英文 `en_us`。

### 指令

- `/randomkeys list`：查看目前世界的亂鍵白名單。
- `/randomkeys add <translation-key>`：OP 等級 2，加入原版／Mod KeyMapping；玩家缺少的新鍵會由下一次 Client 初始化回報補入 Server。
- `/randomkeys remove <translation-key>`：OP 等級 2，從世界白名單與玩家 Server Layout 移除 KeyMapping；Client 恢復該鍵原本本機綁定。
- `/randomkeys reset`：OP 等級 2，重設為預設白名單。
- `/randomkeysclient available [filter]`：列出客戶端已註冊的 KeyMapping translation key，方便加入其他 Mod 鍵位。
- `/!c`：OP 等級 2，切換個人暫時維護模式。

### 安裝與建置

- Fabric 版本需要 Fabric Loader 與 Fabric API。
- Forge 版本使用 Minecraft 1.20.1 / Forge 47.x。
- Java 版本：17。
- GitHub Actions 會同時建置 Fabric 與 Forge；版本更新且建置成功後會把兩個 runtime JAR 發佈到 GitHub Releases。

### 影片／直播通知

- 若準備公開發布影片、實況直播、Shorts／剪輯或其他影音內容，且內容中有安裝、展示或實際使用本 Mod，必須在**錄製、直播或公開發布開始前**先透過本儲存庫的 GitHub Issue 通知 **Ray20123315**。
- 請使用 [`Video / Stream Notice`](https://github.com/Ray20123315/minecraft_key_random_scramble_mod/issues/new?template=video-stream-notice.yml) 表單，至少提供平台、頻道／創作者名稱與預計日期；若已有公開頻道或企劃連結也請附上。
- 這是**通知制**，提交 Issue 不代表 Ray20123315 的贊助、背書、合作或核准。
- 純私人且不提供第三方觀看、不公開發布的錄影不需要提交通知。
- 完整法律條款仍以根目錄 [`LICENSE`](LICENSE) 為準。

### 許可證

- Copyright © 2026 **Ray20123315**。
- **All Rights Reserved**。
- 未經明確書面許可，不授予重新散布、修改後發布、商業使用等權利。
- 完整條款請見根目錄 [`LICENSE`](LICENSE)。

---

## 简体中文

### 玩法

- 只有 Minecraft 正常伤害流程让玩家的**普通 Health（生命值）实际下降**时，才进行一次乱键抽选；盾牌完全挡住、只消耗 Absorption（吸收生命）而普通 Health 没下降时不触发。致死的最后一次 Health 下降仍会触发并保存结果。
- 每次有效扣血会随机选取 **1 个已启用的按键功能**，再进行一次绑定抽选；抽到与当前 binding 完全相同是合法结果，因此本次可能没有可见变化，不会重抽。
- Server 产生的新绑定只会从确认过的常见 PC 键和允许的小键盘数字／运算键中抽取；每个安全键各占 1 份，`未指定（Unbound）` 另占 1 份，**所有结果完全等概率**。`World 1/2`、无效 `key.keyboard.xx`、F13-F25、Windows/Super 等不会被抽到。
- 默认乱键／HUD 列表：向前、向左、向后、向右、跳跃、攻击、放置／使用、潜行、背包、丢弃物品、副手切换、快捷栏 1-9。
- HUD 固定显示在右上角并分成两栏：左栏显示上述核心操作，右栏显示快捷栏 1-9；之后用命令加入的额外 KeyMapping 会附加显示。
- HUD 颜色：当前按键与进入乱键系统前的原始键位相同时显示 **绿色**；不同时显示 **红色**。
- 小键盘数字在简体中文 HUD 显示为 `小键盘0`～`小键盘9`。
- 其他原版或其他 Mod 注册的 KeyMapping 默认完全不修改、也不显示；只有用 `/randomkeys add <translation-key>` 加入后才会被乱键并显示。
- 白名单内的功能即使原本绑定鼠标按键、scancode 或未指定，也可以作为该玩家第一次初始化的原始值；安全键池限制只应用于 Server 后续随机产生的新值。
- 每次实际变更或多人交换前都会先释放按住／点击状态，避免交换后卡住持续移动。
- 连接服务器后，乱键白名单内的 Controls 设置会被锁定；玩家不能通过设置页面或“重置全部”改回去。
- **Server 世界数据是 Layout 的唯一持久权威。** Client Snapshot 只可以补上该玩家在 Server 尚未存在的白名单键；Server 一旦已有该键值，Client 后续回报无法覆盖。
- 白名单、玩家 UUID→Layout 与交换剩余 tick 都保存在该 Minecraft save 的世界数据中；同一 save 的主世界、下界、末地共用同一份数据。旧的 `config/random-keys-survival.json` 不会被新版玩法读取、写入、删除或自动导入。
- 离线时客户端会恢复自己的正常键位；重新进入同一世界时，Server 已保存的 Layout 会先套回。死亡／重生同样不会清除已保存的乱键 Layout。
- 每 3 分钟（3600 server ticks）交换在线且 Layout 完整玩家当前的整套乱键配置：1 人／不足 2 名合格玩家不交换；2 人互换；3 人以上形成一个随机循环，例如 `A → B → C → A`。同一 save 中位于不同维度的玩家仍一起参加。
- 交换倒数本身也会写入世界数据；Server 正常重启后会从先前剩余 tick 继续，Server 关闭期间不消耗倒数。
- 当快捷栏键位纳入乱键时，鼠标滚轮切换快捷栏会被禁止。
- 创造、冒险、旁观模式默认禁止；OP 可使用 `/!c` 暂时开启个人维护模式，离线后自动失效。
- `keepInventory`（死亡不掉落）会持续被强制为 `false`，无法保持开启。
- 支持繁体中文 `zh_tw`、简体中文 `zh_cn`、英文 `en_us`。

### 命令

- `/randomkeys list`：查看当前世界的乱键白名单。
- `/randomkeys add <translation-key>`：OP 等级 2，加入原版／Mod KeyMapping；玩家缺失的新键会由下一次 Client 初始化回报补入 Server。
- `/randomkeys remove <translation-key>`：OP 等级 2，从世界白名单与玩家 Server Layout 移除 KeyMapping；Client 恢复该键原本本地绑定。
- `/randomkeys reset`：OP 等级 2，重置为默认白名单。
- `/randomkeysclient available [filter]`：列出客户端已注册的 KeyMapping translation key，方便加入其他 Mod 键位。
- `/!c`：OP 等级 2，切换个人临时维护模式。

### 安装与构建

- Fabric 版本需要 Fabric Loader 与 Fabric API。
- Forge 版本使用 Minecraft 1.20.1 / Forge 47.x。
- Java 版本：17。
- GitHub Actions 会同时构建 Fabric 与 Forge；版本更新且构建成功后会把两个 runtime JAR 发布到 GitHub Releases。

### 视频／直播通知

- 如果准备公开发布视频、直播、Shorts／剪辑或其他影音内容，并且内容中安装、展示或实际使用本 Mod，必须在**录制、直播或公开发布开始前**先通过本仓库的 GitHub Issue 通知 **Ray20123315**。
- 请使用 [`Video / Stream Notice`](https://github.com/Ray20123315/minecraft_key_random_scramble_mod/issues/new?template=video-stream-notice.yml) 表单，至少提供平台、频道／创作者名称和预计日期；如果已有公开频道或项目链接也请附上。
- 这是**通知制**，提交 Issue 不代表 Ray20123315 的赞助、背书、合作或批准。
- 纯私人、不会提供第三方观看且不会公开发布的录制不需要提交通知。
- 完整法律条款仍以根目录 [`LICENSE`](LICENSE) 为准。

### 许可证

- Copyright © 2026 **Ray20123315**。
- **All Rights Reserved**。
- 未经明确书面许可，不授予重新分发、修改后发布、商业使用等权利。
- 完整条款请见根目录 [`LICENSE`](LICENSE)。

---

## English

### Gameplay

- A scramble draw occurs only when Minecraft's normal damage pipeline causes the player's **normal Health to actually decrease**. Fully blocked shield hits and absorption-only loss do not trigger it. The final lethal Health decrease still triggers and is persisted.
- Each qualifying Health loss selects exactly **one enabled control** and performs one binding draw. Drawing the control's current binding is valid, so a damage event may produce no visible binding change and is never rerolled.
- Server-generated bindings are drawn only from the approved common-PC keys and allowed numpad digits/operators. Every safe key has one equal-weight slot and `Unbound` has one additional equal-weight slot. `World 1/2`, unnamed `key.keyboard.xx` gaps, F13-F25, Windows/Super, and other excluded values are never generated.
- Default scramble/HUD list: forward, left, back, right, jump, attack, use/place, sneak, inventory, drop, swap offhand, and hotbar 1-9.
- The HUD is anchored at the upper-right and split into two columns: core controls on the left, hotbar 1-9 on the right. Extra KeyMappings added by command are appended to the HUD.
- HUD colors: **green** means the current binding matches the original binding captured before scrambling; **red** means it differs.
- Numpad digits are shown as `Numpad 0` through `Numpad 9` in English.
- Other vanilla or mod-provided KeyMappings are untouched and hidden by default. They are only scrambled and shown after `/randomkeys add <translation-key>`.
- An enabled mapping's first initialization may legitimately be a mouse binding, scancode, or Unbound. The safe random pool constrains only new values generated by the Server.
- Held/clicked states are released before real mutations and multiplayer exchanges to prevent stuck movement after a binding changes while held.
- Enabled mappings are runtime-locked while connected. Changes made in Controls, including Reset All, are immediately reverted.
- **The Server's per-world saved data is the sole persistent authority for Layouts.** Client snapshots can initialize only enabled keys that are missing on the Server; once a Server value exists, later client snapshots cannot overwrite it.
- The whitelist, UUID→Layout data, and remaining exchange ticks are stored inside each Minecraft save. Overworld, Nether, and End in the same save share one state. Legacy `config/random-keys-survival.json` files are not read, written, deleted, or automatically imported by the new gameplay state.
- Disconnecting restores normal local controls; reconnecting to the same save reapplies the Server-saved Layout. Death/respawn likewise does not clear the persisted scramble state.
- Every 3 minutes (3600 server ticks), online players with complete Layouts exchange their full current Layouts: fewer than 2 eligible players = no exchange; 2 players = mutual swap; 3+ players = one randomized cycle such as `A → B → C → A`. Players in different dimensions of the same save participate together.
- The exchange countdown is persisted too. A normal Server restart resumes from the saved remaining ticks, while offline time does not consume countdown ticks.
- Mouse-wheel hotbar switching is blocked while hotbar mappings are managed by the mod.
- Creative, Adventure, and Spectator are blocked by default. OPs can use `/!c` for a temporary personal maintenance bypass; it expires on disconnect.
- `keepInventory` is continuously forced to `false`.
- Localized for Traditional Chinese `zh_tw`, Simplified Chinese `zh_cn`, and English `en_us`.

### Commands

- `/randomkeys list` — show the current world's scramble whitelist.
- `/randomkeys add <translation-key>` — OP level 2; add a vanilla/mod KeyMapping. A newly missing key is initialized by the client's next initialization snapshot.
- `/randomkeys remove <translation-key>` — OP level 2; remove a KeyMapping from the world whitelist and player Server Layouts; the Client restores its original local binding.
- `/randomkeys reset` — OP level 2; restore the default whitelist.
- `/randomkeysclient available [filter]` — list registered client KeyMapping translation keys, including keys from other mods.
- `/!c` — OP level 2; toggle the personal temporary maintenance bypass.

### Install and build

- Fabric build requires Fabric Loader and Fabric API.
- Forge build targets Minecraft 1.20.1 / Forge 47.x.
- Java: 17.
- GitHub Actions builds both loaders; a version update that builds successfully publishes both runtime JARs to GitHub Releases.

### Video / stream notice

- If you plan to publicly release a video, livestream, Short/clip, or other audiovisual content in which this Mod is installed, shown, demonstrated, or materially used, you must notify **Ray20123315** through this repository's GitHub Issues **before recording, streaming, or public publication begins**.
- Use the [`Video / Stream Notice`](https://github.com/Ray20123315/minecraft_key_random_scramble_mod/issues/new?template=video-stream-notice.yml) form and provide at least the platform, channel/creator name, and planned date; include a public channel/project URL when available.
- This is a **notice requirement**, not an endorsement or approval process. Filing an issue does not imply sponsorship, endorsement, partnership, or approval by Ray20123315.
- Private recordings that are not shared with third parties or publicly published do not require notice.
- The root [`LICENSE`](LICENSE) remains the controlling legal text.

### License

- Copyright © 2026 **Ray20123315**.
- **All Rights Reserved**.
- No redistribution, modified publication, commercial use, or other additional rights are granted without explicit written permission.
- See the root [`LICENSE`](LICENSE) file for the complete terms.
