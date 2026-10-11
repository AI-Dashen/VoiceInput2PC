# 万能输入法 v0.7 Windows 历史

## 已实现源码

接收端主窗口、托盘、首次设置、配对与错误窗口显示“万能输入法”；内部程序名、配置目录、协议和 URI 不变。电脑接收端版本元数据为 0.7.0。

“全部历史”通过真实 SQLite 查询显示，每页 100 条。上一页/下一页可浏览全部保留记录，不将展示页大小当成保留上限；无自动清理。查询可同时指定关键词、开始时间与结束时间。时间使用电脑本地时间，格式 YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS；空边界不限制，结束日期包括全天。查询会回到第一页；刷新保留当前查询和页码。

选择 TXT、MD、JSON、CSV 后点击“导出查询结果”，系统保存文件对话框选择目的地。导出会应用当前输入框的查询条件，包含全部匹配记录，不限当前页。UTF-8 导出先写目的目录临时文件，成功后替换目标；失败不覆盖原文件。内容来自消息记录，不包含配对信息或会话令牌。CSV 公式防护由 Relay.export_history 实施。

每条确认消息送往 Windows 当时的焦点；输入前后焦点检查由接收核心和 win_input 完成。GUI文案与这一行为一致。

## 本机测试范围

2026-10-12：在当前主机执行 tests/test_history_browser.py 的 6 项测试全部通过。测试使用真实 SQLite 和 235 条记录，覆盖三页浏览、关键词与本地日期查询、无匹配、无效查询保留原查询、四种完整导出、数据库失败不覆盖原文件。Python 编译检查通过。

这些结果证明源码与纯历史逻辑，不证明 Windows Tk 窗口、文件对话框、托盘、SendInput 或 EXE 实际运行。Windows 真机、打包和焦点切换验收仍为 PENDING。

## Windows 打包入口

在 Windows 准备项目 .venv（requirements.txt）及已有可信 vendor/tailcat/windows/tailcat.exe，然后执行：

```powershell
./scripts/build_windows_release.ps1 -Version 0.7.0
```

脚本不会默认下载依赖。只有明确授权新下载后才使用 -FetchTailcat。构建使用原 VoiceInput2PCReceiver.spec，保持 VoiceInput2PCReceiver.exe 和 _internal 目录结构；生成的ZIP必须整包解压。版本校验、首次设置窗口和接收运行验证使用v0.7品牌。

当前工作树缺少 Windows Tailcat EXE，且本机不是 Windows；未生成或验证Windows EXE，不能称Windows成品已完成。根级版本文案断言已随v0.7更新。

## 后续完成：Windows成品已生成

原Windows构建阻塞已由GitHub Actions解决。run 38159766736在windows-latest成功；99项测试执行，2项跳过，其余通过；构建脚本的版本/包结构与Windows启动检查成功。成品下载并回读核对SHA256，文件Download/VoiceInput2PC-v07/万能输入法-Windows-v0.7.0.zip（32525156字节）。无需用户自行编译，完整解压并运行VoiceInput2PCReceiver.exe；保持_internal目录。此结果不替代用户实际电脑的最终验收。代码已由用户最新GitHub授权commit/push，旧“不commit/push”记录为历史状态。
