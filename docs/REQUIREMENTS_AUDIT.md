# 需求逐项核对

核对日期：2026-11-17。对象：Java 17 / Spring Boot 3.5.7 的本地单实例 Demo，包括页面工作台。以当前源码与本轮重新执行的测试为准。

结论：原题五项业务功能、五个设计问题和列出的技术验收标准均已有实现及证据，满足本地 Demo 范围。工程记录已形成；本轮更新的 Prompt、附件及核对记录仍需随最终交付提交。个人能否解释关键代码与完成面试讲解，需要本人排练确认，不能由自动测试认证。

## 交付要求

| 要求 | 结论 | 核对依据 |
| --- | --- | --- |
| 项目根目录的可落地方案 | 满足 | `DEMO_IMPLEMENTATION_PLAN.md` 位于包含pom.xml的Git根目录；包括范围、数据流、接口、表结构、任务拆分、验收和演进 |
| Java17、Spring Boot3.x，无容器依赖 | 满足 | pom固定Java17/release17及Boot3.5.7；启动不依赖容器、独立数据库、Redis或前端构建工具 |
| 一条命令启动 | 满足，有环境前提 | README给出Maven Wrapper启动命令；只需JDK17，首次下载需要网络。Windows已实测，其他操作系统没有实机证据 |
| README完整 | 满足 | 启动、页面使用、接口、架构、指标口径、配置和限制均齐全 |
| 可复现演示及异常路径 | 满足 | `DemoSmoke`和`requests/demo.http`；本轮10步全部PASS，包含404/410/429 |
| 完整Prompt记录 | 已补齐到导出时点，待随本轮修改提交 | `docs/prompts/conversation.md`保留原始公开消息；补入最近对话、移除误收录的自动摘要，保存两张用户截图；`scripts/Export-Prompts.ps1`可再次导出 |
| AI代码审查与故障定位 | 有证据 | `docs/review.md`、`docs/troubleshooting.md`记录鉴权路径绕过、类型截断、协议错误和并发更新等问题及修复；这是AI协作审查，不冒充人工审查 |
| 能解释架构、取舍、十倍流量演进 | 材料满足，个人讲解待确认 | 方案第2、14节及`docs/decisions.md`；已有约8分钟讲解顺序，但未声称用户完成排练 |
| 全页面操作（追加需求） | 满足桌面Demo流程 | 工作台覆盖商品切换、分享生成/复制/打开、统计、编辑标题、上下架、删除/恢复和错误入口；浏览器证据见`evidence/workbench-browser.md` |

## 业务功能与设计问题

| 要求 | 实现与行为 | 证据 |
| --- | --- | --- |
| 商品/分享人/渠道短链 | 三元组唯一约束，相同组合复用；支持wechat/moments/other | LinkService、schema.sql；LinkServiceTest、LinkRepositoryTest、演示步骤1/6 |
| key生成与碰撞处理 | SecureRandom正long转Base62；数据库主键兜底，最多3次尝试；区分业务幂等冲突与真正key碰撞 | LinkServiceTest包含第三次成功、连续碰撞503、数据库故障不盲重试 |
| 跳转与缓存选择 | 302 + no-store，目标路径由服务器生成；每次检查商品状态，301可能绕过检查和统计 | PageController、方案第6节、演示步骤3/5 |
| 首屏Open Graph | 服务端HTML输出title/description/image/url/type；卡片与跳转入口分离 | card.html、PageService、ShareFlowTest、演示步骤2 |
| PV/UV与归因汇总 | 有效跳转尝试计PV；签名Cookie身份去重UV；支持渠道、分享人及组合分组、过滤、日期范围 | StatsRepositoryTest、演示步骤4/6；全局UV独立去重，不累加分组UV |
| 不存在key与缓存穿透 | 非法key在查库前拒绝；合法缺失key缓存30秒，同key原子加载；缓存有界并叠加限流 | LinkServiceTest覆盖过期、并发只加载一次、创建覆盖负缓存和故障不误缓存 |
| 下架或删除降级 | `/s`返回410且不计数；`/c`返回200不可售卡片；旧短链保留并读取最新商品状态 | ShareFlowTest、演示步骤9、工作台恢复测试 |
| 空标题/缺失图片 | 默认标题与描述；图片HTTP接口直接返回默认PNG，不依赖浏览器onerror | MediaController、ShareFlowTest、演示步骤8 |
| 枚举和简单刷量 | 随机key、全局/IP令牌桶、创建/统计限流；签名Cookie、10秒固定桶、已识别爬虫排除 | AbuseGuardTest、StatsPolicyTest、VisitorIdentityTest、StatsRepositoryTest、演示步骤10 |

图片边界：Demo只使用内置白名单资源，处理引用缺失、未匹配资源或启动时商品图文件不存在。未实现任意外链图片的404探测、代理或重试；外部商品源/图床接入属于已说明的后续演进。

## 明确列出的验收标准

| 验收项 | 结果 |
| --- | --- |
| 编解码互逆 | Base62CodecTest验证边界；两个属性测试各10,000次，分别验证正long往返与独立生成合法key的规范性 |
| 非法、超长、特殊字符 | 覆盖null、空、零、前导零、正负号、空格、中文、Emoji、百分号、下划线、超long和12位输入 |
| 不存在短链友好页面 | HTTP演示与集成测试验证404 HTML，无异常堆栈 |
| 商品下架后两类入口降级 | 已验证跳转410与分享卡片不可售文案；删除、恢复也有覆盖 |
| UV去重与反刷口径 | README明确签名Cookie、有效PV、固定时间桶、爬虫规则、跨分组UV不可累加及绕过边界 |
| 核心逻辑独立测试 | Base62、StatsPolicy、VisitorIdentity等普通JUnit测试；StatsRepositoryTest直接创建H2并实例化服务，不启动Spring Boot或HTTP服务 |

## 本轮重新执行的证据

1. `.\mvnw.cmd -B -ntp verify`：48项测试，0失败、0错误、0跳过；其中包含两个各10,000次属性测试；Spotless与JAR打包通过。见`evidence/requirements-audit-verify.txt`。
2. 将本轮JAR复制到`.tools/requirements-audit.jar`，在18084启动独立H2文件库；显式设置匹配的PUBLIC_BASE_URL及演示Token。未访问用户8080数据库。
3. 显式执行DemoSmoke，10步全部PASS，最终PV=3、UV=2。见`evidence/requirements-audit-demo.txt`。
4. 停止该临时实例并以同一数据库重启，再执行verifyOnly：短链、归因、PV/UV和商品删除状态均保留。见`evidence/requirements-audit-persistence.txt`。

普通verify不执行DemoSmoke，因此48项测试与10步实网演示是两份不同证据。测试状态文件`target/demo-state.json`当前指向本轮隔离端口；对其他端口使用verifyOnly前，应先在对应环境执行完整演示。

## 交付前仍需明确的边界

- 公开OG输出已完成，真实微信抓取、平台缓存和JS-SDK未实测；本地localhost不能被社交平台抓取。
- 单实例H2/Caffeine、同步尽力写统计。统计故障可以丢事件，换IP/清Cookie等不能彻底防刷；不是分布式生产方案。
- 十倍流量先可能触发配置的429保护；放宽后数据库读写和聚合争抢连接池可能成为瓶颈。尚未做容量压测，不能承诺QPS。
- 上轮手机视口设置未生效；有响应式CSS，但不能据此声称移动端实机验收通过。这不是原题必需项。
- “4–6小时”为题目建议和原计划预算，无完整工时记录，不宣称已证明实际投入符合该时间。
- 原方案任务复选框保留计划原貌，不代表尚未开发；当前核对以本文与验证日志为准。不能把“排练完成”替用户勾选。
- 本轮修改尚未commit/push；需要把更新后的Prompt、截图与验收报告纳入最终提交。已存在的代码提交与origin跟踪分支可见，但本轮未检查GitHub实时状态。
