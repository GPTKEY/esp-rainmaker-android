# 02_20260808 主机配置同步——Android App 修改计划

- 日期：2026-08-08
- 仓库：`GPTKEY/esp-rainmaker-android`
- 分支：`codex/existing-wifi-reuse-first-pairing`
- 前置计划：`01_20260808_existing_wifi_reuse_first_pairing_plan.md`
- 对应主机仓库：`GPTKEY/uart_echo` / `codex/rainmaker-cloud-integration`
- 对应主机计划：`docs/architecture/80_20260808_mobile_configuration_host_change_plan.md`

---

## 1. 目标

在现有 RainMaker Android App 基础上，把手机端从“设备添加/基础控制客户端”扩展为主机的**远程状态与配置前端**。

本计划不在 Android 端复制一套业务状态机。统一数据方向：

```text
主机 AppCore authoritative state
        ↓ RainMaker report
      Cloud / Local
        ↓
     Android App
        ↓ user edit/request
      Cloud / Local
        ↓
主机 RainMaker adapter
        ↓
AppCore command / configuration
        ↓
主机重新上报 authoritative state
        ↓
Android 最终显示收敛
```

核心原则：

> Android 可以表达用户意图，但不能把“本地已经点过/保存过”当成主机已经生效。

因此所有可写配置都必须接受主机最终回报校正。

---

# 2. App 必须区分三个状态概念

设备详情页不能继续只判断“设备在线不在线”。至少需要理解：

```text
CloudOnline
RemoteControlEnabled
业务参数实际状态
```

其中主机新增：

```text
RemoteControlEnabled    Boolean    READ ONLY
```

它表示主机 AppCore 当前真正允许远程控制的 effective 权限。

Android **只能读取，不能修改**。

## 2.1 UI 状态矩阵

| CloudOnline | RemoteControlEnabled | App 行为 |
|---:|---:|---|
| false | false | 显示离线/缓存状态；禁止写入 |
| true | false | 实时可查看；全部受控设置只读/置灰 |
| true | true | 实时可查看；允许进入受支持的远程配置 |
| false | true | 显示“设备允许远控，但当前云离线”；禁止实际写入 |

App 内可以派生：

```text
controlAvailable = cloudOnline && remoteControlEnabled
```

但不要把该派生状态代替两个原始状态。

原因：用户需要知道“为什么不能控制”。

---

# 3. RemoteControlEnabled 的 App 规则

## 3.1 只读

Android 不提供：

```text
[允许远程控制] Switch
```

来修改设备授权。

该授权必须由设备本机 UI 设置。

App 只展示：

```text
远程控制权限：已允许
```

或：

```text
远程控制权限：设备端未授权
```

## 3.2 控件门禁

当 `RemoteControlEnabled=false`：

- WorkMode 不可修改；
- OutputState 不可修改；
- Low/High Threshold 不可修改；
- 后续普通远程设置不可修改；
- 仍允许查看液位、温度、电池、节点在线状态、阈值当前值、模式当前值等只读信息。

推荐页面提示：

```text
设备当前为只读模式。
请在设备本机开启“允许远程控制”后再修改设置。
```

不要显示成“设备离线”，因为 CloudOnline 可能仍为 true。

## 3.3 主机仍是最后安全门

Android 置灰只是用户体验优化，不是安全边界。

即使 App 因缓存、并发或版本差异错误发出了写请求，主机仍必须能够拒绝。

---

# 4. APP-RM8-A：基础设备配置页面

第一阶段先完成普通 Param 配置，不同时展开 Schedule/Automation。

## 4.1 参数识别

App 对主机固定模型至少识别：

### Host Controller

```text
OutputState
WorkMode
LoRaAvailable
NodeCount
OnlineNodeCount
WiFiRSSI
CloudOnline
OfflineMode
RemoteControlEnabled
```

### Liquid Monitor

```text
LevelPercent
LevelValid
SourceBound
SourceOnline
SourceAddress
Temperature
TemperatureValid
BatteryVoltage
BatteryValid
ThresholdsValid
LowThreshold
HighThreshold
```

实现时优先复用现有 RainMaker Device/Param 模型解析能力，不为本项目重新创建第二套网络协议。

## 4.2 未知参数兼容

Android 遇到未来新增 Param：

- 不崩溃；
- 不假设所有 Param 都必须有本项目定制 UI；
- 可以由通用参数 UI 展示，或安全忽略；
- 已知关键 Param 使用本项目优化后的页面体验。

这样主机增加诊断参数时不要求旧 App 同步发布才能继续使用。

---

# 5. APP-RM8-B1：液位阈值配置

液位上下阈值属于一组关联设置，UI 应按一组编辑，而不是两个完全独立的即时 Switch。

建议页面：

```text
液位控制阈值

低液位阈值     30 %
[────●────────────]

高液位阈值     80 %
[──────────●──────]

[保存]
```

## 5.1 本地校验

提交前至少校验：

```text
0 <= low < high <= 100
```

前端校验只是减少无效请求；主机仍会再次校验。

## 5.2 一组提交

优先使用 RainMaker 支持的 multi-param/bulk 写能力，一次提交 low/high。

如果 SDK 层实际只能分开提交，则 App 必须：

- 仍按一个编辑事务展示；
- 避免提交过程中用户继续修改；
- 以主机最终重新上报的 low/high 为准；
- 任一请求被拒绝时重新加载整个阈值组。

## 5.3 权威回写

点击保存后可以显示短暂“正在应用”，但不能立即把本地草稿永久当成成功值。

正确：

```text
用户保存 25/85
→ 请求发出
→ 主机接受/持久化
→ 主机回报 25/85
→ 页面确认生效
```

失败：

```text
用户保存 25/85
→ 主机拒绝/持久化失败
→ 主机仍回报旧值 30/80
→ 页面恢复 30/80 并提示失败
```

---

# 6. APP-RM8-B2：工作模式配置

`WorkMode` 保持协议整数值，Android 负责本地化显示。

V1 映射：

```text
FILL   → 蓄水
DRAIN  → 排水
TIMER  → 定时
MANUAL → 手动
```

实际协议值以主机稳定枚举为准，Android 不自行重新编号。

建议 UI：

```text
运行模式
[ 蓄水 ▼ ]
```

或单选列表。

规则：

- `RemoteControlEnabled=false` 时不可编辑；
- 提交后等待主机权威值；
- 主机拒绝时恢复当前模式；
- App 不复制自动模式业务逻辑。

---

# 7. APP-RM8-B3：输出控制

OutputState 属于即时控制，不属于持久化配置草稿。

App 可以显示 Toggle/Button，但要根据已知 WorkMode 做体验性门禁：

```text
MANUAL → 允许用户操作
其他模式 → 控件置灰，并提示“当前模式由自动控制接管”
```

但主机仍是最终判定者。

不能仅凭 Android 本地 WorkMode 决定是否发送，因为状态可能刚刚变化。

提交后的最终显示必须来自主机 `OutputState` 回报。

---

# 8. 参数页面组织建议

设备详情可逐步组织为：

```text
设备状态
├ 云连接
├ 远程控制权限
├ LoRa 状态
├ 节点数量
└ RSSI

液位信息
├ 当前液位
├ 温度
├ 电池
├ 来源节点
└ 在线状态

控制
├ 工作模式
└ 本机输出

设置
├ 液位阈值
├ 定时任务
├ 场景
└ 联动规则
```

不要求第一版一次完成全部页面。

第一阶段优先：

```text
RemoteControlEnabled 状态
+ WorkMode
+ OutputState
+ Low/High Threshold
```

这四项可以验证完整的“读状态 → 权限门禁 → 写请求 → 权威回写”闭环。

---

# 9. APP-RM8-C：Schedule 与 Scene

在普通配置稳定后再接入 RainMaker 已有 Schedule / Scene 能力。

## 9.1 Schedule

手机端负责编辑：

- 时间；
- 星期/日期；
- 目标 Param/Scene；
- 启停；
- 删除/修改计划。

例：

```text
周一～周五 06:30
→ WorkMode = FILL
```

## 9.2 Scene

Scene 用于一组目标状态，例如：

```text
维护模式
├ WorkMode = MANUAL
└ OutputState = OFF
```

手机端重点是：

- 列表；
- 新建/编辑；
- 激活；
- 删除；
- 显示主机/云端返回的执行结果。

## 9.3 权限语义不得由 Android 猜测

主机计划会在正式启用 Schedule/Scene 前拆分 direct remote、schedule、scene 的来源权限。

Android 不应写死：

```text
RemoteControlEnabled=false → 所有 Schedule 必然停止
```

也不能写死相反结论。

App 应按主机/云协议明确的产品语义展示。

---

# 10. APP-RM8-D：联动规则

手机端需要明确区分：

```text
RainMaker Cloud Automation
```

和：

```text
Host Local Rule
```

它们不是一回事。

## 10.1 Cloud Automation

适合：

- 跨 RainMaker 设备；
- 非安全关键便利性联动；
- 在线通知和场景。

尽量复用现有 RainMaker Android SDK / App 中已有 Automation 能力。

## 10.2 Host Local Rule

核心联动由主机离线执行，Android 只是规则编辑器。

概念 UI：

```text
新增联动

如果
[ 水箱1 ▼ ]
[ 液位 ▼ ]
[ 小于 ▼ ]
[ 30 % ]

并且
[ 工作模式 ▼ ]
[ 等于 ▼ ]
[ 蓄水 ]

执行
[ 本机输出 ▼ ]
[ 打开 ]

[保存]
```

Android 不执行规则，不维护定时判断线程，也不要求 App 常驻后台。

规则保存后：

```text
Android
→ Rule configuration request
→ 主机持久化
→ 主机重新上报规则摘要/版本
→ Android 显示主机权威配置
```

---

# 11. Local Rule V1 的 App 范围

第一版不要做任意脚本编辑器。

建议只支持主机声明的结构化能力：

```text
Trigger
Condition 1..N
Action 1..N
Enabled
Name
```

UI 控件由主机声明/协议支持的枚举驱动：

- 可选数据源；
- 可选参数；
- 可选比较符；
- 合法值/范围；
- 可选动作。

禁止 Android 自己创造主机不支持的运算符或动作。

---

# 12. 状态刷新与冲突处理

## 12.1 页面进入

进入设备页时优先读取当前 Node/Device/Param 权威状态。

不要只依赖上次本地缓存初始化控件。

## 12.2 写入中

对于 B 类配置可使用：

```text
IDLE
EDITING
SUBMITTING
WAITING_AUTHORITATIVE_UPDATE
SUCCESS / ERROR
```

这些只是 App UI 状态，不是设备业务状态。

## 12.3 多端修改

可能同时存在：

- 主机屏幕修改；
- 手机 A 修改；
- 手机 B 修改；
- Web 修改；
- Schedule/Scene 修改。

因此 Android 不做“客户端最后写入者就是权威”的假设。

一旦收到更新后的主机参数，应刷新当前显示；若用户仍在编辑 Draft，可提示“设备设置已在其他位置更新”，避免无提示覆盖。

V1 可以采用简单策略：

- 未编辑：自动更新；
- 正在编辑：保留 Draft，但标记底层值已变化；
- 保存时继续由主机做最终校验。

---

# 13. 错误与用户提示

至少区分：

```text
设备离线
设备在线但未授权远程控制
当前模式禁止该操作
参数非法
请求失败/超时
主机返回的最终值与请求不同
```

不要把所有错误统一显示成“控制失败”。

这对后续排查 CloudOnline、权限、业务门禁和持久化问题非常重要。

---

# 14. App 与主机的稳定协议契约

Android 只依赖产品语义，不依赖主机 EEZ 实现。

| 契约 | Android 行为 |
|---|---|
| Param name/type | 按稳定模型解析 |
| bounds | 生成/限制输入范围 |
| RO/RW | RO 永远不发写请求 |
| RemoteControlEnabled | 只读，决定受控 UI 是否可编辑 |
| CloudOnline | 区分连接问题和授权问题 |
| WorkMode enum | 稳定映射为本地化显示 |
| Threshold pair | 一组编辑/校验/回写 |
| host reject | 显示错误并恢复权威状态 |
| unknown param | 安全忽略或走通用显示 |
| revision/update | 以最新主机上报刷新 |

禁止：

- 把 `eez_*`、`flg_*` 等主机 UI 内部变量名写进 Android 协议；
- 手机自己写 NVS 语义；
- Android 复制主机的工作模式/阈值业务判断作为最终安全边界；
- 为本项目绕过 RainMaker SDK 再造一套平行云配置协议，除非后续能力确认确实无法由现有 RainMaker 模型表达。

---

# 15. 实施工作包

建议 App 按以下顺序推进：

```text
APP-RM8-A1
识别 RemoteControlEnabled + CloudOnline
→ 设备页“在线 / 只读 / 可控”状态
→ 全局远程写控件门禁

APP-RM8-A2
WorkMode + OutputState
→ 统一权威回写
→ 模式相关 UI 门禁

APP-RM8-A3
LowThreshold + HighThreshold
→ Draft/Apply
→ 成对校验
→ 权威回写/错误恢复

APP-RM8-B
Schedule + Scene
→ 复用 RainMaker 现有能力
→ 配合主机 source/permission 新语义

APP-RM8-C
Cloud Automation + Host Local Rule Editor
→ 两类联动明确区分
→ 本机规则配置同步

APP-RM8-D
OTA / diagnostics UI

APP-RM9
动态多 LoRa 节点设备 UI
```

其中 APP-RM8-A1～A3 应优先完成，因为它们可以最快形成用户可见的完整配置能力，同时验证主机与 App 的双向状态闭环。

---

# 16. 与首次配网计划的关系

`01_20260808_existing_wifi_reuse_first_pairing_plan.md` 继续负责：

```text
设备首次添加
→ 检测是否已有可用 Wi-Fi
→ 复用或重新配置
→ 完成 RainMaker 绑定
```

本 `02` 计划负责绑定完成后的长期使用：

```text
设备详情
→ 状态查看
→ 权限判断
→ 参数配置
→ Schedule / Scene
→ 联动规则
```

两者不应混在同一个首次配网 Activity/流程中。

---

# 17. 验收条件

## 第一阶段

- App 能显示设备云在线状态；
- App 能显示主机端 `RemoteControlEnabled`；
- `RemoteControlEnabled=false` 时设备在线仍可查看，但受控项不可写；
- App 不存在远程开启该权限的入口；
- `RemoteControlEnabled=true` 且在线时，WorkMode/Output/Threshold 配置开放；
- 所有修改最终以主机重新上报值为准；
- 主机端修改设置后，App 能更新；
- App 修改后，主机屏幕最终能看到同一权威状态。

## Schedule / Scene 阶段

- 定时和场景使用 RainMaker 正式模型；
- App 不自行假设 direct remote 权限与 Schedule 权限完全等价；
- 执行结果能够反映主机最终状态。

## Local Rule 阶段

- App 可创建/修改/启停主机支持的结构化规则；
- 规则真正保存在主机端；
- App 退出、手机关机、互联网中断后，本机核心规则仍继续运行；
- 云端 Automation 与本机 Rule 在 UI 中有明确区别。

---

# 18. 非目标

当前不做：

- 手机端远程授权自己获得控制权；
- 把整个主机配置序列化成一个万能 JSON Param；
- 在 Android 后台常驻执行核心联动；
- 在 App 内复制主机控制算法；
- 第一版就实现通用脚本语言；
- 第一版就实现 RM9 动态多节点完整 UI；
- 因增加本项目页面而破坏 RainMaker App 原有通用设备兼容能力。

---

# 19. 当前结论

Android 后续的核心不是增加几个 Slider/Switch，而是形成稳定的远程配置交互语义：

```text
先知道设备是否在线
→ 再知道设备端是否授权远控
→ 用户编辑合法配置
→ 提交请求
→ 等待主机权威状态回报
→ 页面最终收敛
```

`RemoteControlEnabled` 只读状态是整个手机配置体系的入口门禁；阈值和模式优先实现；Schedule/Scene 复用 RainMaker 能力；核心联动规则由主机离线执行，App 只负责编辑和同步。
