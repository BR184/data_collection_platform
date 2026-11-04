# 老平台MR元数据API修复交付

本文负责老平台本次定向JAR修复的构建与部署边界；维护至老平台MR数据源退役。平台其余发布规则保持原归属。

## 适用制品

- 基线：用户确认前开发账号会话包，SHA-256 `65d01e0b3a498fca28d4a56c223c57a08ea0ab8e9532d3d9bb73df7ef99d7147`，含旧延期标签停写补丁。
- 新制品：`D:/projects/data_collection_platform_deploy/crowncad-spider-mr-api-fix-20261104/crowncad-spider-0.0.1-SNAPSHOT.jar`；134,980,422字节，SHA-256 `c1a96ae9929c513ee96ae715f6701604be6b33ab64349cc1ad8165f6321fd9c5`。
- 制品、同目录VERIFICATION.json与sha256文件含私有部署环境上下文；JAR包含既有凭据，只在本地受控交付，不纳入Git。

## 修复合同

项目名称、状态、合并时间/合并人从同一次MR API响应取得；“扫描问题已说明”也从同一响应的标签判定。项目标签仍按中文全角“项目：”取首个；无标签保留“未标注项目名”，免走查扫描时按旧规则保留“无需标注”。开放/关闭/合并状态分别写OPEN/CLOSED/MERGED，合并时间按API偏移转换至服务器本地时区，再保持原秒级存储格式。旧网页元数据解析链已删除。

实际仅修改MR API DTO和MR解析DAO两个class，新增合并人DTO与元数据映射class；原六参数DTO构造入口、Java 9字节码、其它class、全部配置/凭据、依赖及静态资源保持。复用已有API请求，不增加每条MR的请求次数。共用MR入口适用于CC及DGM，二者认证配置保持。

## 构建与验证

从干净任务分支，在仓库根运行以下入口，路径参数由维护者指定；输出和临时目录必须新建，不覆盖已交付制品：

```text
python scripts/legacy-mr-parser/run.py --baseline-jar <批准的会话包JAR> --legacy-source <老源码目录> --jdk-home <JDK目录> --work-dir <本工作树.tmp内的新目录> --output-dir <交付新目录>
```

源文件与基线JAR必须匹配source-contract.json。入口使用JAR原依赖编译所需类，先在旧版执行同一组回归，再在修复版通过11项；最终校验只允许两个class替换和两个class新增，配置、延期停写class及其余条目逐项保持。旧版11项中8项业务断言失败，修复后11/11通过，包含实际MR解析→ExcelBo→入库对象；外部详情/评论及目录为离线替身，未启动应用或连接数据库。

### 本地真实链路验收已通过（2026-11-04）

审核输入cac6e6ca：使用上述同一JAR原class，在任务自有GitLab16.11.10、MySQL8及Mongo4.4上调用真实`getConcurrentByTargetDateAndUrl`、`updateWalkThrough`和`cleanDumPlicateData`。qaflex原MR47882/47884/43696的业务标签和评论重建为测试项目9的!1/!2/!3，分别覆盖已合并、开放、免扫描。项目、状态、合并证据、模块及走查值在API预期、解析、写入、去重及独立SQL五处一致；去重前后均3行。既有11项为离线回归，本次3样例补足真实HTTP和数据库写入。

审核方已核对入口源码、对照表、SQL回读及JAR摘要，基础验收通过；未修改业务代码，无需重打包。证据归本任务`.tmp/legacy-mr-local-qaflex-20261104/`，自有容器停止、卷保留。下一步由用户按现场步骤替换后单次采集。

验证范围限于本次元数据修复及三条单走查记录。未断言扫描/注释率/提交数指标，也未验证重复组消除或多走查分组分支。样例C无评论时的走查日期观察到UTC文本按本地解析的8小时差异，为原有行为，保留记录，不扩大本次修复范围。生产全量与负载仍由现场验证。

## 现场步骤

1. 备份当前JAR和配置，停止旧Java进程并确认退出。
2. 同名替换、核对SHA-256后启动一个后端实例；不修改会话/token及数据库配置。
3. 只触发一次CC采集，避免与00:00/12:00定时任务交叠；客户端Ctrl+C不会取消后端任务。
4. 采集更新成功且数据清理完成后，核对MR24509为CC2026R2、MERGED、2025-10-18 08:53:48（服务器Asia/Shanghai）、合并人田川；再向新平台拉取。

任务取消、抓取/清理重叠和失败后替换数据的问题仍属旧平台既有行为，本包未扩展修复。生产全量结果由现场验证；不能把离线11项回归当作生产采集通过。

## 页面结构的历史结论

现场两账号读取的API及HTML一致，当前旧选择器确实失配；这不能证明近期发生过GitLab升级。官方[12.8.0标签helper](https://github.com/gitlabhq/gitlabhq/blob/v12.8.0/app/helpers/labels_helper.rb)仍用旧badge，[12.9.0](https://github.com/gitlabhq/gitlabhq/blob/v12.9.0/app/helpers/labels_helper.rb)已存在gl-label-link；[15.10.0 MR列表](https://github.com/gitlabhq/gitlabhq/blob/v15.10.0/app/views/projects/merge_requests/_merge_request.html.haml)保留issuable-status，[15.11.0](https://github.com/gitlabhq/gitlabhq/blob/v15.11.0/app/views/projects/merge_requests/_merge_request.html.haml)已删除，16.11.10结构与现场一致。过去正确数据的具体来源，仍需核对当时运行制品、请求页面及刷新记录，不能由当前截图追认。
