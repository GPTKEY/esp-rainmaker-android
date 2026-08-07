# 01_20260808 首次 RainMaker 配对复用现有 Wi-Fi 方案

## 1. 背景

主机端 `GPTKEY/uart_echo` 的 RainMaker BLE + Security 2 配网正在按以下原则收口：

- 设备屏幕只显示极简状态，不承担“是否换 Wi-Fi”的详细交互；
- Wi-Fi 是否已经可用、是否继续使用当前网络、是否重新配置，由手机 App 呈现和选择；
- 新 Wi-Fi 凭据只作为 RAM candidate 使用；设备只有在拿到 `GOT_IP` 后才允许写入 `config_store`；
- 如果设备原有 Wi-Fi 已经可用，继续使用时不得重复下发密码，也不得重复写 Flash/NVS；
- 设备进入普通联网运行后继续使用现有 5s → 10s → 30s → 60s → 300s 退避重连。

本文件只规划 Android App 侧修改。设备端实现由 `GPTKEY/uart_echo` 的 `codex/rainmaker-cloud-integration` 分支继续推进。

## 2. 当前 Android 代码现状

当前仓库已有两类相关能力：

1. `BLEProvisionLanding.java` 已有能力路由：PoP → Claim → BLE local control skip-WiFi → Wi-Fi/Thread。
2. 已存在 `showSkipWifiProvisioningDialog()`，但它只在 `BLE local control + challenge response` capability 分支触发，并不是“设备当前已有可用 Wi-Fi”判断。
3. `BleWifiProvisionActivity.java` 已支持 BLE 下重新扫描和重新提交 Wi-Fi，但属于已连接设备的重新配网页面，不等同于首次 RainMaker 添加时的“复用当前网络”。
4. 当前首次添加流程中 Claim capability 的路由优先于现有 skip-WiFi 判断，所以不能直接把现有 skip 对话框视为本需求已经完成。

## 3. 目标流程

```text
扫描/二维码进入设备
↓
BLE + Security 2 / PoP
↓
Claim（保持现有 RainMaker 兼容顺序）
↓
查询设备当前 Wi-Fi 状态
↓
┌─────────────────────────┬──────────────────────────┐
│ 设备已有可用 Wi-Fi       │ 当前没有可用 Wi-Fi        │
└────────────┬────────────┴────────────┬─────────────┘
             ↓                         ↓
显示当前网络可用                  进入现有 Wi-Fi 配网页
             ↓                         ↓
[继续使用当前网络]               扫描/手动输入 SSID
[重新配置 Wi-Fi]                 输入密码
      ↓            ↓                   ↓
继续使用       进入现有 Wi-Fi 配网页   provision/apply
      ↓            ↓                   ↓
不发送新凭据   新 candidate             GOT_IP
不要求密码     ↓                        ↓
      ↓        GOT_IP                设备提交 config_store
      └──────────────┬──────────────────┘
                     ↓
                完成设备添加
```

## 4. 手机端职责

手机端只负责交互和协议调用，不拥有设备 Wi-Fi 持久化策略。

需要实现：

- BLE/Security 2 建链后能够读取设备当前标准 Provisioning Wi-Fi status；
- 当设备报告 `CONNECTED` 且带有有效 SSID/IP 时，显示“设备当前网络可用”；
- 提供两个明确动作：
  - `继续使用当前网络`
  - `重新配置 Wi-Fi`
- 选择“继续使用”时：
  - 不调用 `set_config(ssid,password)`；
  - 不要求用户重新输入密码；
  - 继续完成 RainMaker 添加/映射流程；
- 选择“重新配置”时：
  - 复用现有 Wi-Fi scan / manual SSID / password 页面；
  - 正常调用标准 Provisioning `set_config + apply_config`；
  - 等待设备返回 CONNECTED 后继续。
- 当设备报告无可用网络、连接失败或没有保存凭据时：直接进入现有 Wi-Fi 配置流程，不增加无意义提示。

## 5. 不应做的事情

- 不把设备保存的 Wi-Fi 密码读回手机；设备端不会提供该能力。
- 不根据“存在 SSID”就判断网络可用；必须以设备当前连接状态/标准 Provisioning status 为准。
- 不修改 RainMaker Claim/User-Node Mapping 的持久化语义。
- 不在 Android 端缓存设备 Wi-Fi 密码作为后续自动恢复来源。
- 不把首次添加和“已绑定设备重新配 Wi-Fi”混成同一个状态机；UI 可复用，业务语义应区分。

## 6. 推荐修改点

### 6.1 `BLEProvisionLanding.java`

目标：调整首次添加路由，在 Claim 完成后的 Wi-Fi 阶段优先判断设备是否已经联网。

建议：

- 保留 PoP / Claim 的原有顺序；
- 新增“查询当前 Wi-Fi 状态”步骤；
- 不再只依赖 `BLE local control + challenge response` capability 才允许 skip Wi-Fi；
- 把现有 `showSkipWifiProvisioningDialog()` 的思想重构成“current Wi-Fi reuse”判断，但不要直接复用原条件。

### 6.2 Wi-Fi 首次配置页面

现有 `WiFiScanActivity` / `WiFiConfigActivity` 继续作为“重新配置 Wi-Fi”入口。

要求：

- 不改变标准 `ESPDevice.provision()` 的新凭据提交语义；
- 用户主动选择重新配置时才进入；
- 设备返回认证失败/AP 不存在时继续使用现有错误提示。

### 6.3 `BleWifiProvisionActivity.java`

该页面主要是已绑定设备的 BLE 重新配网，不应直接承担首次添加状态机。

可以复用：

- Wi-Fi scan UI；
- SSID/password 校验；
- ProvisionListener 进度展示。

但应避免复制另一份互相漂移的 provision 逻辑。若后续抽公共 helper，应以最小重构为原则。

## 7. 设备端协议约定

设备端将提供以下行为，手机实现不得自行猜测：

1. BLE Provisioning session 启动后，设备会静默检查/尝试已保存 Wi-Fi。
2. 如果当前旧 Wi-Fi 已获得 IP，标准 Wi-Fi status 可以返回 `CONNECTED`，并提供：
   - SSID
   - IP
   - BSSID
   - channel
   - auth mode
3. 如果用户没有提交新凭据，则设备不会重新写 `config_store`。
4. 如果手机提交新 SSID/password：
   - 设备只在 RAM 中保存 candidate；
   - 尝试连接；
   - `GOT_IP` 后才提交 `config_store`；
   - 失败时丢弃 candidate，旧持久化凭据不被覆盖。
5. 手机不得依赖设备返回明文旧密码。

## 8. UI 文案建议

设备当前已有可用 Wi-Fi：

```text
设备当前已连接网络：<SSID>

继续使用当前网络，无需重新输入密码。
```

按钮：

```text
继续使用当前网络
重新配置 Wi-Fi
```

没有可用 Wi-Fi 时直接进入网络选择，不额外弹框。

## 9. 验收场景

至少一次性验证以下场景：

1. 首次添加、设备没有 Wi-Fi：正常选择 AP → 输入密码 → 添加成功。
2. 设备已有可用 Wi-Fi：手机提示当前 SSID → 继续使用 → 不要求密码 → 添加成功。
3. 设备已有可用 Wi-Fi：选择重新配置 → 新网络 GOT_IP 后添加成功。
4. 重新配置时输入错误密码：失败，设备旧持久化凭据不被覆盖。
5. 设备有历史凭据但 AP 当前不存在：不得显示“当前网络可用”，应进入 Wi-Fi 配置。
6. Claim 成功但 Wi-Fi 配置失败：不得把节点误标为完整添加成功。
7. 返回/取消流程不会留下重复 BLE session 或重复 Activity。

## 10. 与主机端联调边界

手机端实现完成后，与 `uart_echo` 统一做一次联调即可，不要求每个小步骤单独测试。

联调重点：

- QR / PoP / Security 2；
- 当前 Wi-Fi status 读取；
- continue-current-network 分支；
- reconfigure 分支；
- wrong password rollback；
- Claim / User-Node Mapping；
- 重启后 Wi-Fi + RainMaker 恢复。

## 11. 实施记录

实现分支：`codex/existing-wifi-reuse-first-pairing`。

本轮按计划落地：

- 新增标准 `prov-config / TypeCmdGetWifiStatus` 状态读取；
- 仅 `CONNECTED + 有效 SSID + 有效 IPv4` 才允许提示复用；
- Claim 顺序保持不变，Claim 成功后再检查当前 Wi-Fi；
- “继续使用当前网络”通过独立 `KEY_REUSE_CURRENT_WIFI` 进入后续映射/添加流程；
- 该分支显式不调用 `ESPDevice.provision()`，因此不会发送新 SSID/password，也不会执行 `set_config + apply_config`；
- “重新配置 Wi-Fi”继续复用现有 `WiFiScanActivity / WiFiConfigActivity / ProvisionActivity`；
- 查询失败、超时、无有效网络时静默回退现有 Wi-Fi 配网；
- Thread-only 设备保持原流程；
- 旧 BLE local-control skip 代码暂保留，但不再作为首次添加“当前 Wi-Fi 可用”的判断依据。

最终实机验收仍按第 9 节 7 个场景一次性执行。
