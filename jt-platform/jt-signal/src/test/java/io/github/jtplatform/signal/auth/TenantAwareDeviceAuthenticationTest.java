package io.github.jtplatform.signal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.yzh.web.model.entity.DeviceDO;

/** 租户状态与未建档策略如何影响接入判定。判定全部发生在这一侧，档案接口只提供事实。 */
class TenantAwareDeviceAuthenticationTest {

    private static final String TERMINAL = "13800000001";

    @Test
    void inactiveTenantIsRefusedEvenThoughTheDeviceIsArchived() {
        DeviceAuthenticationService service = remoteService(
                UnregisteredDevicePolicy.ALLOW,
                Optional.of(new DeviceInformation(
                        TERMINAL, TERMINAL, TERMINAL, "京A00001", "tenant-a", false)));

        assertThat(service.authenticate(device(TERMINAL)).allowed()).isFalse();
    }

    @Test
    void activeTenantIsAdmittedAndCanonicalFactsAreMergedIn() {
        DeviceAuthenticationService service = remoteService(
                UnregisteredDevicePolicy.REJECT,
                Optional.of(new DeviceInformation(
                        TERMINAL, TERMINAL, TERMINAL, "京A00001", "tenant-a", true)));

        DeviceAuthenticationDecision decision = service.authenticate(device(TERMINAL));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.device().getPlateNo()).isEqualTo("京A00001");
    }

    @Test
    void sourcesWithoutTenancyAreNeverTreatedAsInactive() {
        // 缺省的租户字段表示「该来源没有租户概念」，绝不能被当成「租户不可用」，
        // 否则 allow-all 与 local-list 模式会在升级后集体拒绝设备。
        DeviceAuthenticationService service = remoteService(
                UnregisteredDevicePolicy.REJECT,
                Optional.of(new DeviceInformation(TERMINAL, TERMINAL, TERMINAL, "京A00001")));

        assertThat(service.authenticate(device(TERMINAL)).allowed()).isTrue();
    }

    @Test
    void unarchivedDeviceFollowsTheConfiguredPolicy() {
        assertThat(remoteService(UnregisteredDevicePolicy.ALLOW, Optional.empty())
                .authenticate(device(TERMINAL)).allowed()).isTrue();
        assertThat(remoteService(UnregisteredDevicePolicy.REJECT, Optional.empty())
                .authenticate(device(TERMINAL)).allowed()).isFalse();
    }

    @Test
    void localListModeIgnoresTheUnregisteredPolicy() {
        // 「不在名单里」本身就是拒绝的理由，不该被未建档策略放宽。
        DeviceAuthenticationService service = new DeviceAuthenticationService(
                terminalId -> Optional.empty(),
                DeviceAuthMode.LOCAL_LIST,
                null,
                UnregisteredDevicePolicy.ALLOW);

        assertThat(service.authenticate(device(TERMINAL)).allowed()).isFalse();
    }

    private static DeviceAuthenticationService remoteService(
            UnregisteredDevicePolicy policy, Optional<DeviceInformation> answer) {
        return new DeviceAuthenticationService(
                terminalId -> answer,
                DeviceAuthMode.REMOTE_API,
                RemoteUnavailablePolicy.DENY,
                policy);
    }

    private static DeviceDO device(String terminalId) {
        DeviceDO device = new DeviceDO();
        device.setDeviceId(terminalId);
        device.setMobileNo(terminalId);
        return device;
    }
}
