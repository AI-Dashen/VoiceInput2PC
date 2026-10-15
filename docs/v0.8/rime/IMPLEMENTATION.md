# 原生中文输入引擎

## 已实现

- Android自有 `RimeChineseInputEngine` 和最小JNI，通过官方 librime C API 调用真实候选，不硬编码中文词汇。
- librime 1.17.0-1 的8个Termux官方二进制包在项目内下载/解包；没有安装新系统、没有全局软件安装。
- APK打包包含完整10个动态库（9库闭包+JNI）；Termux只是Bionic原生库和编译工具来源，APK运行不需要Termux安装、目录、服务或shell。
- 固定雾凇字典commit：`da1fbe602e38f26db846fa10120ee64c2b0324c0`，来源 `https://github.com/iDvel/rime-ice`。6个cn_dicts文件约46.4MB，全部来自官方固定commit。
- 自有纯Rime核心schema全拼/T9共用原始词典。T9以真实拼音字母到数字的Rime algebra生成独立prism，利用真实解码候选消歧；不是数字转固定中文字典。
- Termux librime包没有Lua插件：没有将原雾凇Lua processors/日期计算器直接复制生产，生产schema只注册实际存在的Rime内置组件。原始t9配置仅保留在native-deps研究记录。
- 候选分页、仅真正commit上屏、退格、重置、关闭session。支持App私有词库学习；无个性化字段切换`*_private` schema（`enable_user_dict:false`）。
- App资源以版本指纹部署，完成后才同步marker；启动不重复复制46MB，升级时真实替换。日志不输出输入正文。

## 依赖隔离

ASR原运行库所需`libc++_shared.so`不动。Rime依赖副本的DT_NEEDED/SONAME严格定位ELF64动态表修改为`librime_cxx.so`。RUNPATH由Termux路径改为`$ORIGIN`。JNI使用16384字节ELF段对齐，原官方库亦为0x4000。原库不被覆盖。依赖指纹存于native-deps/rime-libraries.json。

OpenCC库是Rime链接依赖，但本方案不启用OpenCC过滤器，也不访问Termux默认数据路径。Rime的shared/user/log/staging资源使用App提供的私有目录。不得以Termuxprobe证明整个APK运行成功。

## 真实本机验证

原生引擎的结果（同实际下载字典，不是test fixture）：

- `nihao` → `你好、拟好、你、尼、泥、逆、拟`
- `64426` → `你好、你敢、你搞、蜜柑、米糕、拟好、拟稿`

进程退出码0；日志 `native-deps/probe-final.log`。AppUID真实Android测试为`RimeChineseInputEngineTest`，由总调度安装执行并报告，未执行前不得标DEVICE_VERIFIED。

## 构建/恢复

`./scripts/rime/build-native.sh`编译最小JNI并从隔离依赖收集完整打包库。`./scripts/rime/download-dependencies.sh`仅恢复上述确切官方版本，运行前仍需下载授权；官方版本退仓时需要重新核验。APK封装路径为`android/app/src/main/jniLibs/arm64-v8a`。

## 许可

自有JNI/Java和schema没有复制Trime代码。librime是BSD；雾凇字典GPL-3.0，APK保留其LICENSE与来源，不声称所有组件BSD。官方依赖许可全文随APK放在assets/licenses/rime-native。未来对外分发需对照完整许可要求，此轮是本人本机研究使用。

## App首部署内存修复

真实AppUID第一次测试时Oplus终止目标进程；现场记录约949MB anonymous RSS，未见native crash。定位为原始字典首次编译，而非候选查询。

修复：将词典编译转移至构建阶段，APK只包含完整约58MB已编译table/prism/schema；原始46MB词典不在APK内。`scripts/rime/precompile-data.py`可复现构建，schema源码保留在scripts/rime/schemas。原始词库仍是上述官方固定commit。

JNI完全移除start_maintenance，明确prebuilt_data_dir与staging_dir均为App私有shared/build，避免失败测试遗留半编译缓存覆盖预编译资源，不删除用户词库。运行时不编译或部署词典，Java仅分块复制预编译文件。

全新空user目录、不调用任何maintenance的真实独立原生测试：全拼nihao与T9 64426候选均正常，0.02379秒，peak RSS 14,204KiB。该数字是原生查询Probe，不代表Android Java整体RSS。AppUID重测由主调度记录。
