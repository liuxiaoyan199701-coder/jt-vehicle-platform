package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.NoticePage;
import io.github.jtconsole.notice.NoticeService;
import io.github.jtconsole.security.AuthenticatedOnly;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主动通知的读取与已读标记。
 *
 * <p>权限沿用 {@code dashboard:view}：通知呈现的就是首页要点里那些发现，
 * 能看见其一就该能看见其二，不为同一批内容再造一个权限码。
 */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    private final NoticeService notices;

    public NoticeController(NoticeService notices) {
        this.notices = notices;
    }

    /** 一页通知，逐条带调用者自己的已读态。 */
    @GetMapping
    @RequirePermission(Permissions.DASHBOARD_VIEW)
    public ApiResponse<NoticePage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            AuthorizedPrincipal principal,
            DataScope scope) {
        return ApiResponse.ok(notices.read(principal, scope, page, pageSize));
    }

    /** 铃铛上的数字。每 60 秒被拉一次，因此只回一个数。 */
    @GetMapping("/unread-count")
    @RequirePermission(Permissions.DASHBOARD_VIEW)
    public ApiResponse<UnreadCount> unreadCount(AuthorizedPrincipal principal, DataScope scope) {
        return ApiResponse.ok(new UnreadCount(notices.unreadCount(principal, scope)));
    }

    /**
     * 标记一条已读。
     *
     * <p><b>标注 {@link AuthenticatedOnly} 而不是权限码</b>：已读状态是**每人自己的**，
     * 与改自己的密码同类。走权限码的话会先撞上「只读账号禁止一切非 GET」的集中拦截——
     * 只读用户于是永远清不掉自己的铃铛，而他恰恰是最该收到通知的那类人。
     * {@code dashboard:view} 的把关移到服务层：没有这个权限的账号可见集合为空，
     * 标记自然是空操作。
     */
    @PostMapping("/{id}/read")
    @AuthenticatedOnly
    public ApiResponse<Void> markRead(
            @PathVariable long id, AuthorizedPrincipal principal, DataScope scope) {
        notices.markRead(principal, scope, id);
        return ApiResponse.ok(null);
    }

    /** 全部标为已读——只标这个人看得到的那些。理由同上，见 {@link #markRead}。 */
    @PostMapping("/read-all")
    @AuthenticatedOnly
    public ApiResponse<MarkedCount> markAllRead(AuthorizedPrincipal principal, DataScope scope) {
        return ApiResponse.ok(new MarkedCount(notices.markAllRead(principal, scope)));
    }

    /** 包一层对象而不是裸数字：前端的响应拆包按 {@code data} 是对象来写。 */
    public record UnreadCount(long count) {}

    public record MarkedCount(int marked) {}
}
