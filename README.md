# OneSignalWolf Full Alpha v0.9
Paper 1.20.1 / Java 17

Full-alpha implementation of the current OneSignalWolf design for playtesting and balancing.

## Implemented core
- No fixed wolf role. Random HO + fixed survival mission + random Mission II.
- Personal points; only the highest scorer wins.
- Color code helmets and line-of-sight/range code-name tags.
- Local chat (default 8 blocks, LOS required); meeting chat is unrestricted by the local-chat handler.
- OneSignal radio GUI + `/osw signal ...`; Signal Booster and Signaler HO add words; EMP blocks send/receive.
- Breaker blackout, Night Scope, Flashlight, EMP zones and EMP Shield detection.
- Bodies, forensic cause hint, 10-second delayed report, reporter death cancellation.
- Meeting teleport, vote GUI/command, tie=no exile, exile to spectator.
- Writable note -> sealed will on death; uncollected will expires after 180 seconds.
- Locked main inventory; consumable Backpack unlocks +3 slots, used backpacks drop on death.
- Special items: Night Scope, Backpack, Signal Booster, Emergency Battery, First Aid Kit (SELF/TARGET toggle), Body Armor, Tracker, Flashlight, Knife, Smoke, Handgun, Stun Device, EMP Shield.
- Knife backstab lethal + kill cooldown; handgun one magazine/no ammo item; stun projectile.
- Smoke hides code-name tags across the smoke volume.
- Wildlife timed events using configured spawn points; wildlife/weapon/environment death cause categories recorded for forensic HO.
- ALL SURVIVED branch; otherwise winner/cage placement and winner-only execution switch.
- `/osw stop` resets transient game state.

## Setup
`/osw setup breaker`
`/osw setup emp [radius] [seconds]`
`/osw setup wildlife <id>`
`/osw setup meeting`
`/osw setup cage <n>`
`/osw setup winner`
`/osw setup execution-switch`
`/osw setup list`
`/osw setup tp <meeting|winner|cage|wildlife> [id]`
`/osw setup remove <type> [id]`
`/osw setup remove-look <breaker|emp>`
`/osw setup test <breaker|emp|meeting|wildlife|execution> [id]`

## Admin/playtest
`/osw start`, `/osw stop`, `/osw end`, `/osw item <ID>`, `/osw meeting`, `/osw breaker`, `/osw emp`

Item IDs: `NIGHT_SCOPE BACKPACK SIGNAL_BOOSTER EMERGENCY_BATTERY FIRST_AID BODY_ARMOR TRACKER FLASHLIGHT KNIFE SMOKE HANDGUN STUN EMP_SHIELD`

## Build
GitHub Actions workflow is included at `.github/workflows/build.yml`.
The target artifact is produced by Maven with Paper API 1.20.1 and Java 17.

This is intentionally a Full Alpha: balance values are in `config.yml` and are expected to change after multiplayer tests.

## v0.11 Signal GUI
- 通信機を右クリックすると54スロットのチェスト型GUIを開く。
- 上段で送信先（ALL / 各カラー）を切り替え、英単語をクリック順に組み立てて送信する。
- 通常3語、Signal Boosterで+1語、SIGNALER HOで+1語。
- EMP圏内ではGUIは開けるが送信できず、通信不能表示になる。EMP圏内の受信者にも届かない。
- Signal GUI操作中も無敵にはならず、ダメージを受けるとGUIが閉じて未送信の入力は破棄される。
- 現在の単語数では1ページに収まるため、単語ページ送りは未使用。単語追加時にページ方式へ拡張可能。


## v0.12 Command Assist
- `/osw help` 日本語ヘルプを追加。
- `/osw` の全主要階層にTab補完を追加。
- `vote` は現在の投票候補カラーを補完。
- `bot kill/tp` は存在するBot IDを補完。
- `item` は全特殊アイテムIDを補完。
- `setup tp/remove` は登録済みcage/wildlife IDを補完。
- `setup test wildlife` は登録済み野生生物地点IDを補完。


## v0.13 GM Setup
- `/osw gm` / `/osw gm open`: ゲーム開始前のGM設定GUI
- プレイヤーヘッドから対象を選び、HO・使命II・カラーを個別指定
- 左クリックで候補送り、右クリックでランダムへ戻す
- 使命I「ゲーム終了まで生存」は全員固定
- 未指定項目だけゲーム開始時にランダム抽選
- GUIから直接ゲーム開始可能
- `/osw gm reset`: 全指定をランダムへ戻す
- ゲーム開始後は変更不可
