# 验证记录

日期：2026-11-17，时区 Asia/Hong_Kong。环境：Windows 11、Oracle JDK 17.0.12、Maven Wrapper 3.3.4 / Maven 3.9.9、Spring Boot 3.5.7。以下结果来自实际执行，不是预期清单。

| 验证 | 命令 / 方法 | 结果 | 原始证据 |
| --- | --- | --- | --- |
| 构建与测试 | `.\mvnw.cmd -B -ntp spotless:apply verify` | 44项、0失败/错误/跳过；格式检查与JAR打包成功 | `evidence/verify.txt` |
| 属性测试 | Base62Properties，固定种子20261113/20261114 | 两个属性各10,000次；正整数往返、独立字符串规范性 | 同上 |
| 本地一条命令启动 | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"` | 127.0.0.1:8080，健康UP，文件H2启动 | 启动过程本机实测，核心日志保存在本地`.tools/` |
| HTTP完整演示 | `.\mvnw.cmd -B "-Dtest=DemoSmoke" test` | 10步PASS；PV3/UV2，商品1001已删除 | `evidence/demo.txt` |
| 重启持久化 | 停止当前测试服务 → `java -jar target/short-link-demo.jar --spring.profiles.active=demo` → verifyOnly | 短链和统计保留，商品删除状态保留；未reset | `evidence/persistence.txt` |
| 干净源码构建 | 临时副本仅拷贝pom、Wrapper、src，无target/data；执行verify | 44项测试与格式检查通过 | `evidence/clean-verify.txt` |
| 干净环境HTTP | 副本JAR端口18081，设置对应PUBLIC_BASE_URL | 10步PASS，独立数据文件自动生成 | `evidence/clean-demo.txt` |
| 演示失败退出码 | 将主项目verifyOnly指向另一测试端口，故意与保存状态不一致 | 预期断言失败且退出码1，无重置或商品变更 | `evidence/demo-negative.txt` |
| 页面视觉检查 | 浏览器查看首页、商品删除后的错误页；390px移动视口 | 文案可见、没有堆栈；移动页内容宽390px、无横向溢出 | 本次浏览器观察，非自动截图快照测试 |
| 独立审查 | 另一代理审查并复核修复与现有日志 | 4项问题已关闭 | `review.md` |

44项测试包含属性测试方法本身，不是“44+2个方法”。属性方法内部另有20,000次生成输入校验。核心编码、访客身份、统计策略、限流桶与JDBC测试不启动完整Web服务；验收测试另用MockMvc和真实随机端口Tomcat。

实际发现并先复现的问题：空状态NPE、非规范路径鉴权规则不一致、小数ID截断、HTTP405/415被映射为500、并发标题编辑覆盖商品状态、Windows Wrapper空Target索引。后续测试验证了修复；没有把首次失败输出隐藏成“一次全部成功”。

日志中的中文控制台文本可能因Windows默认GBK/终端编码组合而乱码；HTML响应和源码使用UTF-8，浏览器中文显示已验证。日志保留原始字节；PASS/FAIL、状态码、计数与构建结论可直接核对。

未做真实微信平台抓取、外部商品系统接入、多实例负载和容量压测；不据此宣称生产容量或生产安全审计认证。非Demo配置在集成测试中验证了演示管理路由不存在。

交付收尾时已通过Demo reset恢复当前项目的初始商品，并关闭8080/18081验收进程，避免占用用户启动端口。验收期间的日志保留不变；再次运行verifyOnly前需先执行完整演示生成新的状态文件。Windows已实际执行上述启动与测试命令，其他操作系统未做实机验证。

## 页面工作台补充验收（2026-11-17）

- `mvnw.cmd -B -ntp verify`：48项测试、Spotless和JAR打包通过；含两个各10,000次的属性测试。证据：`evidence/workbench-verify.txt`。
- 新增4项WorkbenchTest，先因缺少接口/页面返回404而失败，随后全部通过。覆盖鉴权、删除占位、恢复不覆盖编辑且保留统计、历史上限和页面CSP；HttpGuardTest补充非demo路由不存在。
- 浏览器验证选择1002、生成/复制/打开、真实PV=1/UV=1、下架/删除/恢复、标题修改、分组和日期边界，以及错误/正确自定义凭证。使用18082的隔离内存库，未操作用户8080服务。
- 详情与限制见 `evidence/workbench-browser.md`。本轮手机视口工具设置未生效，不宣称手机实机验收通过。
