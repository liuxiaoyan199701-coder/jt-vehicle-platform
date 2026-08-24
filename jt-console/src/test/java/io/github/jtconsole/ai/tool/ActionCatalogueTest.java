package io.github.jtconsole.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.ai.action.ActionProposalService;
import io.github.jtconsole.ai.action.ActionType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 提示词里的动作清单必须自带字段名与取值格式。
 *
 * <p>{@code propose_action} 的说明写着「params 用下面列出的字段名原样填写」，而清单一度只报
 * 动作名——模型无从知道字段叫什么、什么格式，只能猜，猜错了才在被拒时收到清单。
 * 一个「把围栏改成四边形」的请求为此往返了六轮，其中两轮走到了用户点确认才失败。
 */
class ActionCatalogueTest {
    private final ActionTools tools = new ActionTools(
            Mockito.mock(ToolRunner.class), Mockito.mock(ActionProposalService.class));

    @Test
    void theCatalogueCarriesFieldNamesAndFormatsSoTheModelNeedNotGuess() {
        String catalogue = tools.describeAvailableActions(
                List.of(ActionType.GEOFENCE_CREATE, ActionType.GEOFENCE_UPDATE), true);

        assertThat(catalogue).contains("geofence_create").contains("geofence_update");
        assertThat(catalogue).contains("必填：").contains("可选：");
        // 顶点格式是踩过的那一处：正例、反例都要在模型看得见的地方。
        assertThat(catalogue).contains("points").contains("[lat,lng]").contains("不要写成");
        // 平台管理员必须知道归属租户要自己填。
        assertThat(catalogue).contains("tenantId").contains("平台管理员必填");
    }

    @Test
    void tenantUsersAreNotOfferedTheTenantFieldTheyCannotUse() {
        String catalogue = tools.describeAvailableActions(
                List.of(ActionType.GEOFENCE_CREATE), false);

        assertThat(catalogue).contains("geofence_create");
        assertThat(catalogue).doesNotContain("tenantId");
    }

    @Test
    void anEmptyPermissionSetStillSaysSoInsteadOfListingNothing() {
        assertThat(tools.describeAvailableActions(List.of(), true))
                .contains("没有任何可执行的操作权限");
    }
}
