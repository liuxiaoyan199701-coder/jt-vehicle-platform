# JT 终端推流模拟器

`jt-terminal-simulator` 是 Windows 10/11 x64 桌面程序，用电脑摄像头和麦克风模拟一台
JT/T 808 终端，并在平台下发 T9101 后通过 JT/T 1078 TCP 推送 H.264 与 G.711A。

## 运行前提

- Windows 10 或 11，64 位。
- 开发构建使用 JDK 25；便携版已包含 Java runtime，用户电脑无需安装 JDK。
- 用户自行安装 FFmpeg，构建必须包含 `dshow`、`libx264` 和 `pcm_alaw`。模拟器不会分发
  FFmpeg，也不会自动下载可执行文件。
- 界面可填写 FFmpeg `bin` 目录、`ffmpeg.exe`，或与 `ffmpeg.exe` 同目录的 `ffprobe.exe`。
  留空时从进程启动时的 `PATH` 搜索；修改系统 `PATH` 后需重启模拟器。
- 客户端到平台的 7100/TCP 可达。
- 媒体节点在 T9101 中下发的真实 IP 与 TCP 端口从客户端可达。实例 N 的主、子码流通常为
  78N1/TCP、78N2/TCP，但模拟器始终采用报文实际值，不硬编码端口。

可用以下命令检查 FFmpeg。`dshow`、`libx264`、`pcm_alaw` 三项都必须有输出：

```powershell
$ffmpeg = "C:\path\to\ffmpeg.exe"
& $ffmpeg -hide_banner -version
& $ffmpeg -hide_banner -devices 2>&1 | Select-String "dshow"
& $ffmpeg -hide_banner -encoders 2>&1 | Select-String "libx264|pcm_alaw"
& $ffmpeg -hide_banner -list_devices true -f dshow -i dummy 2>&1
```

最后一条命令用于列出摄像头和麦克风。枚举完成后出现 `Error opening input file dummy` 是预期行为；
没有摄像头的构建机可以正常运行测试和打包，但无法启动预览或推流。

## 构建与启动

开发态使用 JDK 25：

```powershell
$env:JAVA_HOME = "D:\path\to\jdk-25"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd jt-platform
mvn -B -ntp -pl jt-terminal-simulator -am install
mvn -ntp -f jt-terminal-simulator/pom.xml javafx:run
```

生成 Windows 便携 ZIP：

```powershell
cd jt-platform\jt-terminal-simulator
.\package-windows.ps1 -JdkHome $env:JAVA_HOME
```

产物位于 `target/dist/jt-terminal-simulator-windows-x64.zip`。解压后直接运行
`JT Terminal Simulator.exe`，再在界面中选择 `ffmpeg.exe`（也可填写其 `bin` 目录，或选择
同目录的 `ffprobe.exe`）。检测成功后解析出的 `ffmpeg.exe` 绝对路径会自动保存。ZIP 内包含
`runtime`，不包含 FFmpeg 或 ffprobe；不要自行把它们放进发布包。打包脚本会自动用
`--smoke-test` 启动生成的 EXE，该检查不访问摄像头、麦克风、FFmpeg 或网络。

## 终端参数

- 808-2013 使用 12 位终端手机号；808-2019 使用 20 位终端手机号。
- `deviceId` 同时用于注册业务设备和 1078 六字节 BCD 标识，必须为 1～12 位数字。
- 一个进程只模拟一个逻辑通道，同一时刻只推主码流或子码流。
- 默认信令地址为 `127.0.0.1:7100`，逻辑通道为 1。

连接成功后模拟器只维持 808 会话和心跳。播放器或业务系统调用平台开流接口后，平台下发
T9101，模拟器才连接指定媒体节点并开始推流；收到 T9102 后暂停、切换或停止。

## 远程部署检查

1. 平台的设备鉴权配置允许模拟器填写的 `deviceId`。
2. 信令入口 7100/TCP 对模拟器电脑开放。
3. 媒体节点的 `reachableAddress` 是客户端电脑能访问的地址，不是容器内部地址或仅服务端可见地址。
4. 安全组和主机防火墙开放 T9101 实际返回的主/子码流 TCP 端口。
5. 多节点场景不要把 78N1/78N2 放在负载均衡后；设备必须直连被调度节点。

在模拟器电脑上用 PowerShell 验证 TCP 可达性。先将示例地址替换为实际地址：

```powershell
$signalHost = "203.0.113.10"
Test-NetConnection -ComputerName $signalHost -Port 7100

# 收到 T9101 后，使用界面显示的真实媒体节点地址和 TCP 端口。
$mediaHost = "203.0.113.20"
$mediaPort = 7811
Test-NetConnection -ComputerName $mediaHost -Port $mediaPort
```

两次检查的 `TcpTestSucceeded` 都应为 `True`。媒体检查必须使用 T9101 实际返回值，不能用
7100、HTTP 网关地址或容器内部地址代替。

## 故障定位

- 无法连接平台：检查 7100/TCP、终端手机号位数和设备鉴权结果。
- 长时间等待开流：确认外部客户端已调用开流接口，且平台日志显示已下发 T9101。
- T9101 后无画面：检查界面显示的媒体 IP/端口、FFmpeg stderr、摄像头占用和 `reachableAddress`。
- 未发现摄像头：关闭占用摄像头的程序，在 Windows“设置 > 隐私和安全性 > 相机”中允许
  桌面应用访问相机，然后点击“刷新设备”。
- 未找到 FFmpeg：查看界面提示或 `simulator.log` 中列出的配置/PATH 探测来源，重新选择
  `ffmpeg.exe`；若刚把 `bin` 目录加入系统 `PATH`，重启模拟器后再检测。
- 有视频无声音：确认选择了麦克风，FFmpeg 构建包含 `pcm_alaw`，T9101 的 mediaType 为音视频。
- 网络中断：模拟器会重新注册鉴权，但不会向旧媒体地址续推；应由平台重新下发 T9101。
