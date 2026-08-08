# 04_20260809 主机配置同步手机端 CI 验收记录

> 对应计划：`docs/architecture/02_20260808_host_configuration_sync_mobile_app_plan.md`  
> 对应修改记录：`docs/architecture/03_20260808_host_configuration_sync_mobile_app_change_record.md`  
> 实现分支：`codex/host-configuration-sync-mobile-app`  
> 代码验收提交：`49e80bae0634845524f1c6fbd6503df5ac18babe`

---

## APP-HOSTCFG-CI-001：验收工作流

工作流：

`.github/workflows/03_host_configuration_sync_mobile_app.yml`

GitHub Actions Run：

`Host Configuration Sync Mobile App #4`

Run ID：

`31267761477`

验收对象为包含以下最终代码能力的提交：

- 主机配置 UI 写权限投影；
- 最终网络发送前二次门禁；
- WorkMode 1/2/3/4 稳定协议与本地化显示；
- OutputState MANUAL 门禁；
- Low/High 关联范围与 Slider 只提交最终值；
- Param 写请求严格串行；
- 写 ACK 后权威 Param 回读；
- BLE proxy read 与权威 readback 串行保护。

---

## APP-HOSTCFG-CI-002：自动验证结果

本轮 GitHub Actions 最终结果：**SUCCESS**。

全部步骤通过：

```text
Set up job                              PASS
Checkout feature branch                 PASS
Set up JDK 17                            PASS
Set up Android SDK                       PASS
Install Android SDK packages             PASS
Create CI build properties               PASS
Verify implementation boundaries         PASS
Run HostConfigurationPolicy unit tests   PASS
Build debug APK                           PASS
Upload debug APK                          PASS
```

说明：

- `HostConfigurationPolicyTest` 已实际通过，不是仅完成编译；
- `:app:assembleDebug` 已实际通过，因此 Java/Kotlin 互调、Android 资源和新增 BLE/readback 调用边界已经通过编译验证；
- 未使用被 concurrency 取消的旧 run 作为验收依据，本记录只使用最终 Run #4。

---

## APP-HOSTCFG-CI-003：APK Artifact

工作流已成功生成并上传：

`host-configuration-sync-mobile-app-debug-apk`

Artifact ID：

`9024743123`

大小约：

`60.3 MB`

SHA-256 digest：

`41c5231ff1bf1e12e09accfac61473eff3ebb6bbaae8eb5202635fd29f3c506e`

Artifact 由 GitHub Actions Run #4 生成，可用于下一阶段真机安装和联调。

---

## APP-HOSTCFG-CI-004：当前验收边界

自动化验证已经确认：

1. 代码可编译；
2. 主机配置策略单元测试通过；
3. Android 资源与 Java/Kotlin 接口完整；
4. debug APK 可成功生成。

仍需要真机才能最终确认的内容：

1. 主机关闭/开启 `RemoteControlEnabled` 后 App 控件是否按预期即时变化；
2. WorkMode 中文显示与主机收到 1/2/3/4 的实际联调；
3. OutputState 在非 MANUAL / MANUAL 下的实际控制行为；
4. Low/High 拖动只发送释放时最终值；
5. BLE 写入后的 proxy 上报与权威回读是否在真实无线时序下无冲突；
6. 主机主动修改配置后 App 是否始终收敛到主机权威值。

这些属于设备联调验收，不应由编译结果替代。

---

## APP-HOSTCFG-CI-005：结论

当前手机端第一阶段代码已经达到：

```text
实现完成
+ 策略单测通过
+ Android debug 全量编译通过
+ APK artifact 生成成功
```

从代码和 CI 角度可以进入真机联调阶段。

Low/High 双字段 Draft/Apply 专用卡片仍属于可选 UX 增强，不作为本阶段代码验收阻塞项。
