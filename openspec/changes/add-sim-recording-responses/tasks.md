# Tasks

规则同前：本 worktree（sim-revamp 分支）只动 jt-terminal-simulator；只提交不推送不合并；
协议语义以 org.yzh.protocol 的 T9205/T1205/T9201 类与标准为准，不要凭常识猜。

- [x] 1.1 模拟资源配置：条数/时间段/通道，进 SimulatorConfig 持久化
      （照 TripConfig 的 @JsonCreator 包装类型模式，旧配置缺字段可加载）
- [x] 1.2 SignalClient 处理 0x9205 → 生成 T1205 资源列表应答；时间字段 TerminalTime.ZONE
- [x] 1.3 SignalClient 处理 0x9201 → 应答后按指令的地址/端口/通道/时间段连接并推送
      合成回放流（复用既有 1078 推流机；SIM 形态沿用当前配置）；推流结束正常收尾
- [x] 1.4 界面「录像」页签：资源配置表单 + 最近指令与应答状态显示
- [x] 1.5 单测：T1205 编码回环、9201 指令解析出的目标地址与时间段正确、
      资源生成器边界（0 条、跨午夜时间段）
- [x] 1.6 `mvn -pl jt-terminal-simulator -am test` 全绿（含既有回归）；
      `openspec validate add-sim-recording-responses --strict` 通过；如实勾选
- [x] 1.7 git commit（中文说明），不推送不合并
