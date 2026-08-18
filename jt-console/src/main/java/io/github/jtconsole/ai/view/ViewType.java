package io.github.jtconsole.ai.view;

import io.github.jtconsole.security.Permissions;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 可以提议展示的视图。与 {@code ActionType} 一样是**白名单**——不在其中的一律拒绝。
 *
 * <p>动作改数据、视图只呈现，但两者的校验强度不该有差别，因为**视图是自动就去取数的**：
 * 动作卡片至少还有「用户点确认」那一关，用户会看一眼参数；视图渲染出来时中间没有人看。
 * 一个参数越界的视图会让前端去打一个必然失败或代价极大的请求。
 *
 * <p>{@link Payload} 区分事实的来源，这是本模块唯一的结构性分叉：
 *
 * <ul>
 *   <li>{@link Payload#REFERENCE}：指向数据面已存在、可被重新解析的资源。事件是个**指针**，
 *       数据由前端凭用户自己的凭据向既有接口取。</li>
 *   <li>{@link Payload#SNAPSHOT}：本轮对话才产生的派生结论，没有可解引用的地址，只能自带**值**。</li>
 * </ul>
 *
 * <p>分轨依据刻意选「事实从哪来」而不是「前端有没有对应接口」：后者会因为别人加了个接口而失效，
 * 那不是原则。而「模型把三台车的里程和告警编排进同一张图」这件事，无论将来有没有接口都无法被
 * 任何接口重现。
 */
public enum ViewType {

    /** 一台车（或全部在线车辆）的当前位置。只读、参数最少，是这套机制的最小验证形态。 */
    LIVE_MAP("live_map", "实时位置", Payload.REFERENCE, Permissions.MONITOR_VIEW,
            Presentation.INLINE, List.of(), List.of("deviceId")),

    /** 一台车某个时间段的行驶轨迹。跨度上限见 {@link #MAX_TRACK_HOURS}。 */
    TRACK_MAP("track_map", "行驶轨迹", Payload.REFERENCE, Permissions.TRACK_VIEW,
            Presentation.INLINE, List.of("deviceId", "start", "end"), List.of()),

    /**
     * 实时视频。
     *
     * <p><b>唯一一个用引用卡的视图</b>：开流会通过网关向路上那台车下发指令，
     * 这种有真实世界副作用的事不该由 AI 自动触发，必须由用户点一下。
     */
    LIVE_VIDEO("live_video", "实时视频", Payload.REFERENCE, Permissions.VIDEO_PLAY,
            Presentation.REFERENCE_CARD, List.of("deviceId"), List.of("channel")),

    /**
     * 统计图表。快照型——数值由模型转述，没有可解引用的地址。
     *
     * <p>权限只要求能用助手：数据本来就是模型用别的工具在数据范围内查到的，再叠一层没有意义。
     */
    CHART("chart", "统计图表", Payload.SNAPSHOT, Permissions.AI_CHAT,
            Presentation.INLINE, List.of(), List.of()),

    /**
     * 抓拍照片墙。
     *
     * <p>引用型：照片是平台数据面已有、可重新解析的资源，事件里只带设备号与时间窗，
     * 前端拿用户自己的令牌去 {@code /api/media} 取。**绝不把图片本身塞进事件**——
     * 那既撑爆留痕体积，也让权限校验失去意义。
     *
     * <p>直接内联渲染而不是引用卡：查看已经拍好的照片是纯读操作，没有任何真实世界副作用，
     * 与实时视频「开流会向路上那台车下发指令」的性质完全不同。
     */
    PHOTO_GALLERY("photo_gallery", "抓拍照片", Payload.REFERENCE, Permissions.MEDIA_LIST,
            Presentation.INLINE, List.of("deviceId"), List.of("start", "end", "channel"));

    /**
     * 轨迹查询的时间跨度上限。
     *
     * <p>不限的后果很具体：前端会去查一个几年的范围，触发上万点的全表扫描——**AI 就成了自助的
     * 拒绝服务触发器**。与轨迹查询工具描述里「跨度不要超过一天」保持一致。
     */
    public static final int MAX_TRACK_HOURS = 24;

    /**
     * 抓拍查询的时间跨度上限。
     *
     * <p>比轨迹宽是因为抓拍稀疏得多——一台车一天可能就几张，限到 24 小时会让「最近拍到了什么」
     * 这种最自然的问法查不到东西。但仍要有上限，理由同上。
     */
    public static final int MAX_PHOTO_DAYS = 7;

    /** 事实的来源。决定校验管线走哪一支。 */
    public enum Payload {
        REFERENCE,
        SNAPSHOT
    }

    /**
     * 呈现方式。
     *
     * <p>{@link #INLINE} 直接渲染在气泡里，与文字并存；{@link #REFERENCE_CARD} 先给一张卡片、
     * 用户点了才真正加载。
     *
     * <p>分界线不是「轻 / 重」，而是**有没有真实世界的副作用**：只读查询直接渲染，会向车辆下发
     * 指令的（视频开流）必须由人点一下。
     */
    public enum Presentation {
        INLINE,
        REFERENCE_CARD
    }

    private final String wireName;
    private final String label;
    private final Payload payload;
    private final String requiredPermission;
    private final Presentation presentation;
    private final List<String> requiredFields;
    private final List<String> optionalFields;

    ViewType(
            String wireName, String label, Payload payload, String requiredPermission,
            Presentation presentation, List<String> requiredFields, List<String> optionalFields) {
        this.wireName = wireName;
        this.label = label;
        this.payload = payload;
        this.requiredPermission = requiredPermission;
        this.presentation = presentation;
        this.requiredFields = List.copyOf(requiredFields);
        this.optionalFields = List.copyOf(optionalFields);
    }

    /** 字段的类型与取值说明，拼进工具描述。没有特别说明的返回空串。 */
    public static String fieldHint(String field) {
        return switch (field) {
            case "deviceId" -> "（设备号字符串，必须来自查询结果；留空表示全部在线车辆）";
            case "start", "end" -> "（yyyy-MM-ddTHH:mm:ss，跨度不得超过 24 小时）";
            case "channel" -> "（数字，摄像头通道号，不得超过该车的通道数）";
            default -> "";
        };
    }

    public String wireName() {
        return wireName;
    }

    public String label() {
        return label;
    }

    public Payload payload() {
        return payload;
    }

    public String requiredPermission() {
        return requiredPermission;
    }

    public Presentation presentation() {
        return presentation;
    }

    public List<String> requiredFields() {
        return requiredFields;
    }

    public List<String> optionalFields() {
        return optionalFields;
    }

    public boolean knows(String field) {
        return requiredFields.contains(field) || optionalFields.contains(field);
    }

    public static Optional<ViewType> of(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return Optional.empty();
        }
        String needle = wireName.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(needle))
                .findFirst();
    }
}
