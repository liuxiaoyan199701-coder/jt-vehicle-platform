package io.github.jtconsole.domain;

/**
 * 套餐。{@code maxVehicles}/{@code maxAccounts} 为 0 表示不限量；
 * 金额一律以「分」存整数，避免浮点累计误差。
 */
public record Plan(
        long id,
        String name,
        int maxVehicles,
        int maxAccounts,
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
}
