# ESP RainMaker Android 项目文档索引

当前定制目标：将 ESP RainMaker Android App 扩展为主机 `uart_echo` 的首次配网、远程状态、配置与主控制器 Dashboard 客户端，同时保持原 RainMaker 通用设备兼容能力。

---

## 架构与计划

### 01. 首次配网复用现有 Wi-Fi

```text
docs/architecture/01_20260808_existing_wifi_reuse_first_pairing_plan.md
```

负责：

- 首次添加设备；
- 检查设备是否已有可用 Wi-Fi；
- 尽量复用已有 Wi-Fi；
- 必要时再输入 Wi-Fi；
- 完成 RainMaker 绑定。

### 02. 主机配置同步 Android 计划

```text
docs/architecture/02_20260808_host_configuration_sync_mobile_app_plan.md
```

负责：

- `CloudOnline` / `RemoteControlEnabled`；
- `WorkMode`；
- `OutputState`；
- `LowThreshold` / `HighThreshold`；
- Schedule / Scene / Local Rule 后续规划；
- Android 只表达用户意图，主机状态保持权威。

### 03. 主控制器专用 Dashboard 实现

```text
docs/architecture/03_20260817_main_controller_dashboard_implementation.md
```

当前 V1 已完成：

- `Host Controller + Liquid Monitor` 聚合；
- 专用 Compose Dashboard；
- 水位、阈值、模式、输出；
- 云/Wi-Fi/LoRa/远控状态；
- 节点统计和当前液位源；
- 温度、电池；
- 高级参数回退；
- GitHub Actions `assembleDebug` 通过。

---

## 修改与问题记录

### 主控制器 Dashboard V1

```text
docs/changes/01_20260817_main_controller_dashboard_change_and_issue_record.md
```

记录：

- 具体源码修改；
- 页面路由设计；
- 协议数据边界；
- 主机权威状态原则；
- Compose 编译失败原因和修复；
- CI 日志获取问题和解决方法；
- 最终构建状态；
- 后续真机验收项。

---

## 当前开发分支

```text
codex/main-controller-dashboard
```

主控制器 V1 产品源码基线：

```text
dc8d96686177e60da41722de0eba60386f1bea2b
```

后续文档修改会继续产生新的提交，因此排查功能代码时以上述产品源码基线和后续分支 HEAD 一并确认。

---

## 当前状态

```text
首次 BLE/RainMaker 配网能力       已有基础
主机稳定 RainMaker contract       已接入
主控制器专用 Dashboard V1         已完成编译验证
普通 RainMaker Device 兼容         保留
Dashboard 真机 UI/双向参数联调      待验收
Schedule / Scene 专用入口          待完善
动态多 LoRa 节点 RM9               待主机协议扩展
```

项目后续应继续遵守：

```text
Android UI
    -> RainMaker SDK / NetworkApiManager
    -> 主机 RainMaker adapter
    -> AppCore authoritative state
    -> 主机重新上报
    -> Android 收敛
```

不要在 Android 复制主机业务状态机，也不要为了 UI 展示伪造 RainMaker contract 中尚不存在的数据。