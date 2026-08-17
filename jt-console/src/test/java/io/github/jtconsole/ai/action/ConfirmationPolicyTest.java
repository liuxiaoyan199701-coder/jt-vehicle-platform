package io.github.jtconsole.ai.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 确认策略。
 *
 * <p>这里最要紧的不是「配了免确认就免确认」，而是**配了也不管用**的那几个动作——它们是整个
 * 写路径的安全底线，必须有用例钉住，否则以后有人图省事把底线做成「可覆盖的默认值」时没人拦得住。
 */
class ConfirmationPolicyTest {

    @Test
    void byDefaultEverythingNeedsConfirmation() {
        ConfirmationPolicy policy = ConfirmationPolicy.confirmEverything();

        for (ActionType type : ActionType.values()) {
            assertThat(policy.requiresConfirmation(type)).as(type.wireName()).isTrue();
        }
    }

    @Test
    void reversibleActionsCanBeConfiguredToRunWithoutConfirmation() {
        ConfirmationPolicy policy = ConfirmationPolicy.autoExecuting(
                Set.of("vehicle_create", "fleet_create", "alarm_acknowledge"));

        assertThat(policy.requiresConfirmation(ActionType.VEHICLE_CREATE)).isFalse();
        assertThat(policy.requiresConfirmation(ActionType.FLEET_CREATE)).isFalse();
        assertThat(policy.requiresConfirmation(ActionType.ALARM_ACKNOWLEDGE)).isFalse();
        // 没配的仍然要确认
        assertThat(policy.requiresConfirmation(ActionType.VEHICLE_UPDATE)).isTrue();
    }

    @Test
    void theIrreversibleFloorCannotBeConfiguredAway() {
        // 有人把所有动作都勾成免确认——底线动作必须仍然要求确认。
        Set<String> everything = Arrays.stream(ActionType.values())
                .map(ActionType::wireName)
                .collect(Collectors.toSet());

        ConfirmationPolicy policy = ConfirmationPolicy.autoExecuting(everything);

        assertThat(policy.requiresConfirmation(ActionType.VEHICLE_DELETE)).isTrue();
        assertThat(policy.requiresConfirmation(ActionType.FLEET_DELETE)).isTrue();
        assertThat(policy.requiresConfirmation(ActionType.GEOFENCE_DELETE)).isTrue();
        assertThat(policy.requiresConfirmation(ActionType.SEND_TEXT)).isTrue();
        assertThat(policy.requiresConfirmation(ActionType.TENANT_DISABLE)).isTrue();
        assertThat(policy.requiresConfirmation(ActionType.PLAN_UPDATE)).isTrue();
    }

    @Test
    void everyDeletionAndEveryCommandIsOnTheFloor() {
        // 防止将来新增动作时漏掉底线标记：删除类与指令类一律不可免确认。
        for (ActionType type : ActionType.values()) {
            boolean destructive = type.wireName().contains("delete")
                    || type.wireName().startsWith("send_");
            if (destructive) {
                assertThat(type.alwaysConfirm())
                        .as("%s 是不可逆动作，必须标记为永远确认", type.wireName())
                        .isTrue();
            }
        }
    }

    @Test
    void unknownOrFloorNamesInConfigurationAreIgnoredRatherThanFatal() {
        ConfirmationPolicy policy = ConfirmationPolicy.autoExecuting(
                Set.of("vehicle_create", "vehicle_delete", "no_such_action"));

        assertThat(policy.autoExecutableNames()).containsExactly("vehicle_create");
        // 运维需要知道自己勾的哪一项没生效，而不是默默被吞掉。
        assertThat(ConfirmationPolicy.rejectedByFloor(
                Set.of("vehicle_create", "vehicle_delete", "send_text")))
                .containsExactlyInAnyOrder("vehicle_delete", "send_text");
    }

    @Test
    void anEmptyConfigurationFallsBackToConfirmingEverything() {
        assertThat(ConfirmationPolicy.autoExecuting(Set.of())
                .requiresConfirmation(ActionType.VEHICLE_CREATE)).isTrue();
        assertThat(ConfirmationPolicy.autoExecuting(null)
                .requiresConfirmation(ActionType.VEHICLE_CREATE)).isTrue();
        // 只勾了底线动作，等同于什么都没勾。
        assertThat(ConfirmationPolicy.autoExecuting(Set.of("vehicle_delete"))
                .requiresConfirmation(ActionType.VEHICLE_CREATE)).isTrue();
    }

    @Test
    void everyActionDeclaresAnExistingPermissionCode() {
        // 动作复用既有权限码；写错一个字母的后果是「谁都用不了」或更糟「谁都能用」。
        Set<String> known = io.github.jtconsole.security.Permissions.catalog().stream()
                .map(io.github.jtconsole.domain.PermissionDefinition::code)
                .collect(Collectors.toSet());

        for (ActionType type : ActionType.values()) {
            assertThat(known)
                    .as("%s 声明的权限码 %s 不在权限目录中", type.wireName(), type.requiredPermission())
                    .contains(type.requiredPermission());
        }
    }
}
