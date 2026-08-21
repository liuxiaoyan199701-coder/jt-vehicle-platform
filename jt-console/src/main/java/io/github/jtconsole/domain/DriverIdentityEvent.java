package io.github.jtconsole.domain;

/** 0702 驾驶员身份识别事件留痕。 */
public record DriverIdentityEvent(
        Long id,
        String eventId,
        String deviceId,
        int status,
        int cardStatus,
        String name,
        String licenseNo,
        String institution,
        String licenseValidPeriod,
        String idCard,
        Long driverId,
        String deviceTime,
        String receivedAt) {

    /** 0702 status：0 上班（插卡）/ 1 下班（拔卡）。 */
    public boolean cardIn() {
        return status == 0;
    }

    /** IC 卡读取结果非 0 表示读取失败。 */
    public boolean cardReadFailed() {
        return cardStatus != 0;
    }
}
