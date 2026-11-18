# CHANGELOG

## [0.1.0] · 未发布

### 新增
- 仓库建立：BUCT One（`com.buct.one`），规划先行的空仓库
- `docs/规划.md`：总规划 v2（定位、四阶段里程碑、验收标准、Top 8 风险）
- `docs/plan-1-data-layer.md`：数据层详设（SSO 会话复用、CampusSource、provenance、存储、同步、迁移五步）
- `docs/plan-2-roadmap.md`：功能路线图（模块分级、里程碑、企业微信双链路、首屏 IA、不做清单）
- `docs/plan-3-engineering.md`：工程与风险（模块化、CI 体积门禁、凭据、TLS 三级、灰度、回滚）
- `docs/签名接入.md`：正式签名 secrets 配置
- CI 骨架：12MB 体积门禁、PR 体积 delta、包依赖规则检查、签名分支（path 过滤，代码就位后启用）

### 说明
- 代码从零重写，不复用前身「北化课表」的 497 个 Kotlin 文件；只参考其 SSO 与 zf 教务协议实现
- 包名 `com.buct.one`，与旧版 `com.tyust.course` 完全独立，可并存安装
- 版本线从 0.1.0 / versionCode 1 起算