package io.github.jtconsole.domain;

/**
 * 套餐。三个上限均以 0 表示不限量；金额一律以「分」存整数，避免浮点累计误差。
 *
 * <p>{@code maxVehicles}/{@code maxAccounts} 是**存量**上限——「现在拥有多少」，靠一次 COUNT 即可判定。
 * {@code maxAiCallsMonthly} 是**流量**上限——「本自然月消耗了多少次」，没有现成载体可以现算，
 * 必须依赖 {@code ai_usage} 计量表。两类语义不同，但对外都沿用同一个「0 = 不限量」约定。
 */
public record Plan(
        long id,
        String name,
        int maxVehicles,
        int maxAccounts,
        int maxAiCallsMonthly,
        long priceCents,
        int periodMonths,
        boolean enabled,
        String remark,
        String createdAt,
        String updatedAt) {

    public boolean vehicleQuotaExceeded(int current) {
        return maxVehicles > 0 && current >= maxVehicles;
    }

    public boolean accountQuotaExceeded(int current) {
        return maxAccounts > 0 && current >= maxAccounts;
    }

    /**
     * @param usedThisMonth 本自然月已消耗的对话次数
     */
    public boolean aiQuotaExceeded(long usedThisMonth) {
        return maxAiCallsMonthly > 0 && usedThisMonth >= maxAiCallsMonthly;
    }
}
