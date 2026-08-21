# Tasks

规则：本 worktree（ai-geocoding 分支）只做本变更；只提交不推送不合并；
不建迁移（不需要表）。坐标系口径是本变更的灵魂：返回给 create_geofence 的坐标
必须与既有围栏链路一致——先读 geo/CoordTransform 与围栏创建/展示代码确认
存储用的是哪套坐标（高德 API 返回 GCJ-02，如存储是 WGS84 必须经 CoordTransform
转换），并在工具描述里写清楚，禁止两套口径混用。

- [x] 1.1 geo/PlaceSearchClient：高德 place/text POI 搜索 + geocode/geo 回退，
      复用 ReverseGeocoder 的 key/超时/降级模式（查不到返回空列表不抛错）
- [x] 1.2 ai/tool/GeoTools：search_place(keyword, city 可选) 返回至多 5 条候选
      （名称/地址/坐标，坐标系按上方口径），工具描述引导 AI：多候选时先向用户
      确认再建围栏，绝不自选
- [x] 1.3 注册：Key 未配置时 GeoTools 整体不注册（照视觉模型的装配模式）
- [x] 1.4 单测：候选解析、空结果、Key 缺失不注册、坐标转换口径（用已知点断言）
- [x] 1.5 jt-console 全量测试绿；strict 校验；如实勾选；git commit（中文说明）不推送
