package io.github.jtconsole.security;

import io.github.jtconsole.repository.VehicleRepository;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 「这条结论该不该给这个人看」的**唯一一份**判定。
 *
 * <p>首页要点与主动通知看到的必须逐条一致：同一条发现要么两处都可见、要么两处都不可见。
 * 两边各写一遍必然分叉——只要判断差一点，就会出现「铃铛里有但首页要点里没有」，
 * 而用户无从判断该信哪个。所以规则提到这里，两边共用。
 *
 * <p>两条规则：
 * <ul>
 *   <li>引用了范围外车辆的结论，**整条丢弃，不做部分保留**——一条结论的措辞是围绕
 *       它涉及的全部车辆写的，删掉几个设备号并不会让那句话变准确。</li>
 *   <li>范围不覆盖整个租户时，租户级聚合结论（在线率、今日告警总数）一律不给。
 *       聚合数字无法「部分过滤」——「今日告警 47 条」这句话本身就泄露了范围外的信息。</li>
 * </ul>
 *
 * <p>宁可少说，不能多说。
 */
@Service
public class ScopeVisibility {

    private final VehicleRepository vehicles;

    public ScopeVisibility(VehicleRepository vehicles) {
        this.vehicles = vehicles;
    }

    /**
     * 为一次读取解析出可见设备集。
     *
     * <p>一次取出而不是逐条判定：一份要点或一页通知可能引用几十个设备号，
     * 逐条查库会把一次页面加载变成几十次查询。
     */
    public Filter forScope(DataScope scope) {
        // 部门受限即视为「范围不覆盖整个租户」，此时不给聚合结论。
        boolean fullScope = !scope.departmentRestricted();
        return new Filter(fullScope, fullScope ? Set.of() : vehicles.visibleDeviceIds(scope));
    }

    /** 一次读取内复用的可见性判定。 */
    public record Filter(boolean fullScope, Set<String> visibleDevices) {

        public Filter {
            visibleDevices = Set.copyOf(visibleDevices);
        }

        /**
         * @param deviceIds 该条结论涉及的设备号；空表示这是租户级聚合结论
         */
        public boolean visible(Collection<String> deviceIds) {
            if (fullScope) {
                return true;
            }
            if (deviceIds == null || deviceIds.isEmpty()) {
                return false;
            }
            return visibleDevices.containsAll(deviceIds);
        }
    }
}
