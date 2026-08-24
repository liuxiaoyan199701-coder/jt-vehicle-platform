import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * JT/T 808-2013 终端模拟器。
 *
 * <p>没有真实车机时用它验证整条链路：模拟器 → 网关 7100 → 投递 → jt-console → 地图。
 * 走完整的注册（0x0100）、鉴权（0x0102）流程，然后沿一条预设路线循环上报位置（0x0200），
 * 并按 30 秒间隔发心跳（0x0002）。
 *
 * <p>单文件源码程序，无需编译和依赖，直接运行：
 * <pre>
 *   java DeviceSimulator.java --host gateway.example.com --device 013800138000
 *   java DeviceSimulator.java --host gateway.example.com --device 013800138001 --interval 5 --speed 80
 * </pre>
 *
 * <p>{@code --device} 是报文头里的终端手机号，{@code --terminal} 是注册报文里的终端 ID
 * （7 字节），两者是不同的字段。网关的设备鉴权配成 local-list 时只放行清单内的**终端 ID**，
 * 此时必须用 {@code --terminal} 传清单里的值，否则注册能过、鉴权必败，
 * 表现为网关认定设备离线、所有下行指令都回 "Device is offline"。
 *
 * <p>坐标上报的是 WGS-84 原值（与真实终端一致），转 GCJ-02 由 jt-console 负责。
 */
public final class DeviceSimulator {

    private static final Charset GBK = Charset.forName("GBK");

    // ---- 报文标识 ----
    private static final int MSG_TERMINAL_REGISTER = 0x0100;
    private static final int MSG_TERMINAL_AUTH = 0x0102;
    private static final int MSG_LOCATION_REPORT = 0x0200;
    private static final int MSG_HEARTBEAT = 0x0002;
    private static final int MSG_PLATFORM_COMMON_REPLY = 0x8001;
    private static final int MSG_REGISTER_REPLY = 0x8100;

    private static final byte DELIMITER = 0x7E;
    private static final byte ESCAPE = 0x7D;

    private final String host;
    private final int port;
    private final String deviceId;
    private final String terminalId;
    private final String plateNo;
    private final int intervalSeconds;
    private final double baseSpeedKph;
    private final Random random = new Random();

    /** 注册应答里下发的鉴权码，鉴权时必须原样回送。 */
    private String authToken;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private int serialNo = 0;

    // 当前位置状态
    private double lat;
    private double lng;
    private double bearingDegrees;
    private double totalMileageKm;

    private DeviceSimulator(String host, int port, String deviceId, String terminalId, String plateNo,
                            int intervalSeconds, double baseSpeedKph, double startLat, double startLng) {
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.terminalId = terminalId;
        this.plateNo = plateNo;
        this.intervalSeconds = intervalSeconds;
        this.baseSpeedKph = baseSpeedKph;
        this.lat = startLat;
        this.lng = startLng;
        this.bearingDegrees = 90.0D; // 正东出发
    }

    public static void main(String[] args) throws Exception {
        String host = arg(args, "--host", "127.0.0.1");
        int port = Integer.parseInt(arg(args, "--port", "7100"));
        String deviceId = arg(args, "--device", "013800138000");
        String terminalId = arg(args, "--terminal", "SIM0001");
        String plateNo = arg(args, "--plate", "京A12345");
        int interval = Integer.parseInt(arg(args, "--interval", "10"));
        double speed = Double.parseDouble(arg(args, "--speed", "50"));
        // 默认起点：天安门。用于验证坐标转换是否正确——地图上应落在天安门，而非偏移数百米。
        double startLat = Double.parseDouble(arg(args, "--lat", "39.908722"));
        double startLng = Double.parseDouble(arg(args, "--lng", "116.397496"));

        DeviceSimulator simulator = new DeviceSimulator(
                host, port, deviceId, terminalId, plateNo, interval, speed, startLat, startLng);
        simulator.run();
    }

    private void run() throws Exception {
        System.out.printf("连接 %s:%d，终端号 %s，车牌 %s%n", host, port, deviceId, plateNo);
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15000);
        out = socket.getOutputStream();
        in = socket.getInputStream();

        Runtime.getRuntime().addShutdownHook(new Thread(this::closeQuietly));

        register();
        authenticate();

        System.out.println("开始上报位置，Ctrl+C 停止");
        long lastHeartbeat = System.currentTimeMillis();
        int reported = 0;
        while (true) {
            advance();
            sendLocation();
            reported++;
            System.out.printf("[%3d] %.6f, %.6f  速度 %.1f km/h  方向 %.0f°  里程 %.2f km%n",
                    reported, lat, lng, currentSpeed(), bearingDegrees, totalMileageKm);
            drainInbound();

            if (System.currentTimeMillis() - lastHeartbeat > 30_000L) {
                sendMessage(MSG_HEARTBEAT, new byte[0]);
                lastHeartbeat = System.currentTimeMillis();
            }
            Thread.sleep(intervalSeconds * 1000L);
        }
    }

    // ---------------- 协议报文 ----------------

    private void register() throws IOException {
        ByteWriter body = new ByteWriter();
        body.writeWord(11);                       // 省域 ID：北京
        body.writeWord(1);                        // 市县域 ID
        body.writeFixedString("SIMUL", 5, GBK);   // 制造商 ID，5 字节
        body.writeFixedString("JT-SIM-2026", 20, GBK); // 终端型号，20 字节
        body.writeFixedString(terminalId, 7, GBK);     // 终端 ID，7 字节
        body.writeByte(1);                        // 车牌颜色：蓝色
        body.writeBytes(plateNo.getBytes(GBK));   // 车牌号

        sendMessage(MSG_TERMINAL_REGISTER, body.toByteArray());
        System.out.println("已发送注册 0x0100");

        byte[] reply = readMessage();
        if (reply == null) {
            throw new IOException("注册无应答");
        }
        int messageId = ((reply[0] & 0xFF) << 8) | (reply[1] & 0xFF);
        if (messageId != MSG_REGISTER_REPLY) {
            System.out.printf("警告：期望 0x8100，实际收到 0x%04X%n", messageId);
            return;
        }
        // 消息体：应答流水号(2) + 结果(1) + 鉴权码(变长)
        int bodyStart = 12; // 2 msgId + 2 属性 + 6 手机号 BCD + 2 流水号
        int result = reply[bodyStart + 2] & 0xFF;
        System.out.printf("注册应答 result=%d (0=成功)%n", result);
        // 消息体之后还有 1 字节校验码，鉴权码取到它之前为止。
        int tokenLength = reply.length - 1 - (bodyStart + 3);
        if (result == 0 && tokenLength > 0) {
            authToken = new String(reply, bodyStart + 3, tokenLength, GBK);
        }
    }

    private void authenticate() throws IOException {
        if (authToken == null || authToken.isBlank()) {
            throw new IOException("注册应答里没有鉴权码，无法鉴权");
        }
        ByteWriter body = new ByteWriter();
        body.writeBytes(authToken.getBytes(GBK));
        sendMessage(MSG_TERMINAL_AUTH, body.toByteArray());
        System.out.println("已发送鉴权 0x0102");

        byte[] reply = readMessage();
        if (reply != null) {
            int messageId = ((reply[0] & 0xFF) << 8) | (reply[1] & 0xFF);
            System.out.printf("鉴权应答 0x%04X%n", messageId);
        }
    }

    private void sendLocation() throws IOException {
        ByteWriter body = new ByteWriter();
        body.writeDoubleWord(0);                    // 报警标志：无报警
        // 状态位：bit0 ACC 开、bit1 已定位
        body.writeDoubleWord(0b11);
        body.writeDoubleWord((long) Math.round(Math.abs(lat) * 1_000_000));  // 纬度，1/10^6 度
        body.writeDoubleWord((long) Math.round(Math.abs(lng) * 1_000_000));  // 经度，1/10^6 度
        body.writeWord(50);                                                  // 高程，米
        body.writeWord((int) Math.round(currentSpeed() * 10));               // 速度，1/10 km/h
        body.writeWord((int) Math.round(bearingDegrees));                    // 方向
        body.writeBcdTime(LocalDateTime.now());                              // 时间 BCD[6]

        // 附加信息 0x01：里程，单位 1/10 km
        body.writeByte(0x01);
        body.writeByte(4);
        body.writeDoubleWord((long) Math.round(totalMileageKm * 10));
        // 附加信息 0x30：无线信号强度
        body.writeByte(0x30);
        body.writeByte(1);
        body.writeByte(25);
        // 附加信息 0x31：卫星数
        body.writeByte(0x31);
        body.writeByte(1);
        body.writeByte(12);

        sendMessage(MSG_LOCATION_REPORT, body.toByteArray());
    }

    private void sendMessage(int messageId, byte[] body) throws IOException {
        ByteWriter header = new ByteWriter();
        header.writeWord(messageId);
        header.writeWord(body.length & 0x03FF);   // 消息体属性：低 10 位为长度，不分包不加密
        header.writeBcd(deviceId, 6);             // 终端手机号 BCD[6]（2013 版）
        header.writeWord(nextSerialNo());

        ByteWriter message = new ByteWriter();
        message.writeBytes(header.toByteArray());
        message.writeBytes(body);

        byte[] raw = message.toByteArray();
        byte checksum = 0;
        for (byte b : raw) {
            checksum ^= b;
        }

        ByteWriter framed = new ByteWriter();
        framed.writeByte(DELIMITER);
        framed.writeBytes(escape(raw));
        framed.writeBytes(escape(new byte[] {checksum}));
        framed.writeByte(DELIMITER);

        out.write(framed.toByteArray());
        out.flush();
    }

    /** 转义：0x7E → 0x7D 0x02，0x7D → 0x7D 0x01 */
    private static byte[] escape(byte[] raw) {
        ByteWriter escaped = new ByteWriter();
        for (byte b : raw) {
            if (b == DELIMITER) {
                escaped.writeByte(ESCAPE);
                escaped.writeByte(0x02);
            } else if (b == ESCAPE) {
                escaped.writeByte(ESCAPE);
                escaped.writeByte(0x01);
            } else {
                escaped.writeByte(b);
            }
        }
        return escaped.toByteArray();
    }

    private static byte[] unescape(byte[] raw) {
        ByteWriter plain = new ByteWriter();
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == ESCAPE && i + 1 < raw.length) {
                byte next = raw[++i];
                plain.writeByte(next == 0x02 ? DELIMITER : ESCAPE);
            } else {
                plain.writeByte(raw[i]);
            }
        }
        return plain.toByteArray();
    }

    /** 读取一个完整的 0x7E ... 0x7E 报文并去转义，超时返回 null */
    private byte[] readMessage() throws IOException {
        try {
            int value;
            while ((value = in.read()) != -1 && value != (DELIMITER & 0xFF)) {
                // 跳过定界符之前的噪声
            }
            if (value == -1) {
                return null;
            }
            ByteWriter buffer = new ByteWriter();
            while ((value = in.read()) != -1 && value != (DELIMITER & 0xFF)) {
                buffer.writeByte(value);
            }
            byte[] raw = buffer.toByteArray();
            return raw.length == 0 ? null : unescape(raw);
        } catch (java.net.SocketTimeoutException timeout) {
            return null;
        }
    }

    /** 非阻塞地消费平台下行报文，避免接收缓冲堆积 */
    private void drainInbound() throws IOException {
        while (in.available() > 0) {
            byte[] message = readMessage();
            if (message == null || message.length < 2) {
                return;
            }
            int messageId = ((message[0] & 0xFF) << 8) | (message[1] & 0xFF);
            if (messageId != MSG_PLATFORM_COMMON_REPLY) {
                System.out.printf("   收到下行报文 0x%04X%n", messageId);
            }
        }
    }

    // ---------------- 运动模拟 ----------------

    private double currentSpeed() {
        return Math.max(0, baseSpeedKph + random.nextGaussian() * 5);
    }

    /**
     * 按当前速度和方向推进一个上报间隔的距离，并偶尔转向，形成有拐弯的真实轨迹。
     */
    private void advance() {
        double speedKph = currentSpeed();
        double meters = speedKph / 3.6D * intervalSeconds;
        double bearingRad = Math.toRadians(bearingDegrees);

        // 每度纬度约 111320 米；经度需按纬度收缩
        lat += (meters * Math.cos(bearingRad)) / 111320.0D;
        lng += (meters * Math.sin(bearingRad)) / (111320.0D * Math.cos(Math.toRadians(lat)));
        totalMileageKm += meters / 1000.0D;

        // 约 10% 概率转弯，模拟路口
        if (random.nextInt(10) == 0) {
            bearingDegrees = (bearingDegrees + (random.nextBoolean() ? 90 : -90) + 360) % 360;
        } else {
            bearingDegrees = (bearingDegrees + random.nextGaussian() * 3 + 360) % 360;
        }
    }

    private int nextSerialNo() {
        serialNo = (serialNo + 1) & 0xFFFF;
        return serialNo;
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 退出中，无需处理
        }
    }

    private static String arg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    // ---------------- 字节写入辅助 ----------------

    private static final class ByteWriter {
        private final List<Byte> bytes = new ArrayList<>();

        void writeByte(int value) {
            bytes.add((byte) value);
        }

        void writeWord(int value) {
            writeByte(value >> 8);
            writeByte(value);
        }

        void writeDoubleWord(long value) {
            writeByte((int) (value >> 24));
            writeByte((int) (value >> 16));
            writeByte((int) (value >> 8));
            writeByte((int) value);
        }

        void writeBytes(byte[] data) {
            for (byte b : data) {
                bytes.add(b);
            }
        }

        void writeFixedString(String value, int length, Charset charset) {
            byte[] raw = value.getBytes(charset);
            for (int i = 0; i < length; i++) {
                writeByte(i < raw.length ? raw[i] : 0);
            }
        }

        /** 数字串转 BCD，左侧补 0 到指定字节数 */
        void writeBcd(String digits, int byteLength) {
            String padded = digits;
            int expected = byteLength * 2;
            while (padded.length() < expected) {
                padded = "0" + padded;
            }
            for (int i = 0; i < expected; i += 2) {
                int high = Character.digit(padded.charAt(i), 10);
                int low = Character.digit(padded.charAt(i + 1), 10);
                writeByte((high << 4) | low);
            }
        }

        /** BCD[6] 的 YY-MM-DD-hh-mm-ss */
        void writeBcdTime(LocalDateTime time) {
            writeBcd(String.format("%02d%02d%02d%02d%02d%02d",
                    time.getYear() % 100, time.getMonthValue(), time.getDayOfMonth(),
                    time.getHour(), time.getMinute(), time.getSecond()), 6);
        }

        byte[] toByteArray() {
            byte[] result = new byte[bytes.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = bytes.get(i);
            }
            return result;
        }
    }
}
