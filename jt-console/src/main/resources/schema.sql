-- 车辆档案。device_id 是 808 终端手机号，也是与网关投递信封 deviceId 的关联键。
CREATE TABLE IF NOT EXISTS vehicle (
    device_id     TEXT PRIMARY KEY,
    plate_no      TEXT NOT NULL,
    plate_color   TEXT,
    brand         TEXT,
    channel_count INTEGER NOT NULL DEFAULT 1,
    remark        TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

-- 每设备一行的最新状态，位置汇报到达时 UPSERT。实时监控页直接读这张表。
CREATE TABLE IF NOT EXISTS device_status (
    device_id    TEXT PRIMARY KEY,
    online       INTEGER NOT NULL DEFAULT 0,
    last_seen_at TEXT,
    device_time  TEXT,
    lat          REAL,
    lng          REAL,
    gcj_lat      REAL,
    gcj_lng      REAL,
    speed_kph    REAL,
    direction    INTEGER,
    altitude     INTEGER,
    mileage      REAL,
    acc_on       INTEGER,
    positioned   INTEGER,
    alarm_json   TEXT,
    status_json  TEXT,
    updated_at   TEXT NOT NULL
);

-- 轨迹点。lat/lng 是设备上报的 WGS-84 原值，gcj_* 是转换后用于高德地图渲染的坐标。
CREATE TABLE IF NOT EXISTS track_point (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id   TEXT NOT NULL,
    device_time TEXT,
    received_at TEXT NOT NULL,
    lat         REAL NOT NULL,
    lng         REAL NOT NULL,
    gcj_lat     REAL NOT NULL,
    gcj_lng     REAL NOT NULL,
    speed_kph   REAL,
    direction   INTEGER,
    altitude    INTEGER,
    mileage     REAL,
    alarm_json  TEXT
);

CREATE INDEX IF NOT EXISTS idx_track_device_time ON track_point (device_id, device_time);

-- 幂等去重。网关代码明确承认可能重复投递同一 eventId，必须按它判重。
CREATE TABLE IF NOT EXISTS processed_event (
    event_id   TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_processed_created ON processed_event (created_at);

-- 可审计告警事件。围栏删除后仍保留 geofence_name 快照，因此不对 geofence_id 建外键。
CREATE TABLE IF NOT EXISTS alarm_event (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id           TEXT NOT NULL,
    type                TEXT NOT NULL,
    title               TEXT NOT NULL,
    source              TEXT NOT NULL,
    level               TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'OPEN',
    occurred_at         TEXT NOT NULL,
    last_occurred_at    TEXT NOT NULL,
    gcj_lat             REAL,
    gcj_lng             REAL,
    geofence_id         INTEGER,
    geofence_name       TEXT,
    acknowledged_at     TEXT,
    acknowledged_by     TEXT,
    acknowledge_note    TEXT,
    closed_at           TEXT,
    closed_by           TEXT,
    close_note          TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alarm_status_time
    ON alarm_event (status, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_alarm_device_time
    ON alarm_event (device_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_alarm_level_status
    ON alarm_event (level, status);
CREATE INDEX IF NOT EXISTS idx_alarm_source_type
    ON alarm_event (source, type);

-- 持续性报警条件的活动状态。alarm_key 对协议报警为稳定 bit 名，对围栏限速包含围栏 ID。
CREATE TABLE IF NOT EXISTS alarm_condition_state (
    device_id       TEXT NOT NULL,
    source          TEXT NOT NULL,
    alarm_key       TEXT NOT NULL,
    active          INTEGER NOT NULL DEFAULT 0,
    alarm_event_id  INTEGER,
    geofence_id     INTEGER,
    last_seen_at    TEXT,
    updated_at      TEXT NOT NULL,
    PRIMARY KEY (device_id, source, alarm_key)
);

CREATE INDEX IF NOT EXISTS idx_alarm_condition_active
    ON alarm_condition_state (device_id, source, active);
CREATE INDEX IF NOT EXISTS idx_alarm_condition_geofence
    ON alarm_condition_state (geofence_id, active);

-- 第一版只支持 GCJ-02 圆形围栏。
CREATE TABLE IF NOT EXISTS geofence (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    name                TEXT NOT NULL,
    center_gcj_lat      REAL NOT NULL,
    center_gcj_lng      REAL NOT NULL,
    radius_meters       REAL NOT NULL,
    color               TEXT NOT NULL,
    enabled             INTEGER NOT NULL DEFAULT 1,
    alert_on_enter      INTEGER NOT NULL DEFAULT 1,
    alert_on_exit       INTEGER NOT NULL DEFAULT 1,
    speed_limit_kph     REAL,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS geofence_vehicle (
    geofence_id INTEGER NOT NULL,
    device_id   TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    PRIMARY KEY (geofence_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_geofence_vehicle_device
    ON geofence_vehicle (device_id, geofence_id);

CREATE TABLE IF NOT EXISTS geofence_presence (
    geofence_id INTEGER NOT NULL,
    device_id   TEXT NOT NULL,
    inside      INTEGER NOT NULL,
    updated_at  TEXT NOT NULL,
    PRIMARY KEY (geofence_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_geofence_presence_device
    ON geofence_presence (device_id, geofence_id);

-- 轻量运营汇总；last_* 仅用于计算同一自然日内相邻点的距离增量。
CREATE TABLE IF NOT EXISTS vehicle_daily_stat (
    device_id        TEXT NOT NULL,
    stat_date        TEXT NOT NULL,
    distance_km      REAL NOT NULL DEFAULT 0,
    point_count      INTEGER NOT NULL DEFAULT 0,
    moving_points    INTEGER NOT NULL DEFAULT 0,
    max_speed_kph    REAL NOT NULL DEFAULT 0,
    alarm_count      INTEGER NOT NULL DEFAULT 0,
    last_lat         REAL,
    last_lng         REAL,
    last_mileage     REAL,
    last_device_time TEXT,
    updated_at       TEXT NOT NULL,
    PRIMARY KEY (device_id, stat_date)
);

CREATE INDEX IF NOT EXISTS idx_daily_stat_date
    ON vehicle_daily_stat (stat_date, device_id);

-- 组织内单层车队档案。仅增量建表，旧 SQLite 数据库中的车辆默认保持未分配。
CREATE TABLE IF NOT EXISTS fleet (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    code          TEXT NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    manager       TEXT,
    contact_phone TEXT,
    remark        TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fleet_code ON fleet (code);
CREATE INDEX IF NOT EXISTS idx_fleet_name ON fleet (name, id);

-- 当前车辆归属。device_id 为主键，从数据库层保证一辆车最多属于一个车队。
CREATE TABLE IF NOT EXISTS fleet_vehicle (
    device_id  TEXT PRIMARY KEY,
    fleet_id   INTEGER NOT NULL,
    assigned_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fleet_vehicle_fleet
    ON fleet_vehicle (fleet_id, device_id);

-- 终端上传的多媒体文件元数据（摄像头拍照 0x8801 → 0x0801 上传、苏标附件等）。
-- 二进制文件本身保存在网关的 /var/lib/jt-platform/data/signal/multimedia，
-- 这里只存投递信封里带过来的标识与访问地址，供前端列表与展示。
-- (device_id, file_id) 唯一：同一多媒体 ID 的重复投递只保留一条。
CREATE TABLE IF NOT EXISTS media_file (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id      TEXT NOT NULL,
    file_id        INTEGER NOT NULL,
    file_type      TEXT NOT NULL,
    file_format    TEXT,
    file_name      TEXT,
    size           INTEGER,
    access_address TEXT,
    channel_id     INTEGER,
    event_code     INTEGER,
    captured_at    TEXT NOT NULL,
    UNIQUE (device_id, file_id)
);

CREATE INDEX IF NOT EXISTS idx_media_device_time
    ON media_file (device_id, captured_at DESC, id DESC);

-- 设备协议版本。车辆控制 0x8500 等双版本报文在 2011 与 2019 的编码不同
-- （解锁/加锁的字节位置完全不一样），下发前必须知道设备实际注册的版本。
-- 独立成表而不是给 device_status 加列：CREATE IF NOT EXISTS 不会给已存在的
-- 表补列，独立建表天然兼容旧数据库。
CREATE TABLE IF NOT EXISTS device_attribute (
    device_id        TEXT PRIMARY KEY,
    protocol_version TEXT NOT NULL,
    updated_at       TEXT NOT NULL
);
