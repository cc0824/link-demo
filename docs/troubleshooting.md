# 故障定位记录

以下均来自本次开发与本地回归，不是生产事故。

## 1. 商品更新并发回退

- 现象：状态更新已提交为OFF_SHELF，较早开始的标题编辑完成后却变回ACTIVE。
- 定位：事务本身不能避免读已提交隔离级别下的丢失更新。旧实现先读title/status，再把未提交修改的旧字段也写回。
- 复现：真实H2、两个事务、CountDownLatch控制顺序，`titleOnlyPatchCannotRestoreStatusFromAnEarlierRead` 红测断言 `OFF_SHELF` 实得 `ACTIVE`。证据：`evidence/patch-regression-before.txt`。
- 修复：只更新请求中明确提供的白名单字段，更新后重新读取当前行；不把旧状态带入标题更新。
- 回归：最终44项测试包括该并发用例，全部通过。

## 2. 路径解析和鉴权不一致

- 现象：非规范编码路径能到达同一个管理处理器，而早期过滤器没有把它判定为管理接口。
- 定位：过滤器使用原始URI，Web容器和路由使用经过规范化的路径。
- 复现：本地随机端口的真实HTTP测试，非规范请求期望400却实际201；普通未鉴权请求仍401，因此MockMvc普通路径测试不足以捕获。证据：`evidence/path-regression-before.txt`。
- 修复：在任何处理器执行前拒绝固定路由不需要的转义、matrix参数、重复斜线和点段；正常API仍必须提供Token。
- 回归：`HttpPathSecurityTest` 在嵌入式Tomcat下验证修复，最终完整verify通过。

## 3. 小数商品ID被悄悄接受

- 现象：`productId:1001.9` 返回201并为1001创建短链。
- 定位：Jackson默认允许小数截断为Long，Bean Validation收到的已经是1001。
- 修复：禁用浮点转整数，显式拒绝非字符串归因及字符串整型ID；保留未知字段拒绝和Bean Validation。
- 回归：`ShareFlowTest.jsonTypesAndHttpProtocolErrorsAreNotSilentlyCoercedOrReportedAs500`。同时验证405/415不被通用异常处理误报为500。

## 4. Windows Wrapper启动失败

- 现象：官方生成的Wrapper在本机PowerShell5.1报告“Cannot index into a null array”。
- 定位：普通目录的 `Get-Item(...).Target` 为null，脚本直接读取Target[0]。
- 修复：先判断Target是否存在再索引；不改下载源和校验逻辑。
- 验证：Wrapper输出Maven3.9.9与Java17.0.12，随后完整构建、本地启动、HTTP演示成功。另一次写用户 `.m2` 被沙箱权限拦住，经明确授权完成下载；这是运行环境权限，不是应用故障。

## 5. 明确标注的故障注入

`FailureIsolationTest` 注入统计写入故障，仍得到302；注入商品读取故障得到503而不是410；短链读取失败后下一次能重新查库返回404，证明故障不被当作负缓存。注入只发生在测试替身，未破坏本地持久化数据库。
