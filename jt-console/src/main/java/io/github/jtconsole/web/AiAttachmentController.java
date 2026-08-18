package io.github.jtconsole.web;

import io.github.jtconsole.ai.vision.AttachmentStore;
import io.github.jtconsole.ai.vision.AttachmentVisionService;
import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话里用户发送的图片。
 *
 * <p>上传与提问分成两步，是为了让界面能在用户还在打字时就把图显示出来——贴图即见，
 * 而不是发送后才出现。
 *
 * <p>访问边界是**账号**：只能读到自己上传的图。这里不做租户级共享，图片是私人对话的输入。
 */
@RestController
@RequestMapping("/api/ai/attachments")
public class AiAttachmentController {

    private final AttachmentStore store;
    private final AttachmentVisionService vision;

    public AiAttachmentController(AttachmentStore store, AttachmentVisionService vision) {
        this.store = store;
        this.vision = vision;
    }

    @PostMapping
    @RequirePermission(Permissions.AI_CHAT)
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file, AuthorizedPrincipal principal)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("请选择要发送的图片");
        }
        // 未启用识别时明确拒绝而不是照单收下：收下了也没人看，用户会以为模型看见了却装作没看见。
        if (!vision.available()) {
            throw new IllegalStateException("平台未启用图片识别能力，暂时无法发送图片");
        }
        String id = store.save(principal.accountId(), file.getBytes(), file.getContentType());
        return ApiResponse.ok(Map.of("id", id));
    }

    /**
     * 回读自己上传的图片，供对话气泡与历史还原显示。
     *
     * <p>缓存设成私有且较长：文件名是 UUID，内容永不变更，浏览器缓存住不会读到旧图。
     */
    @GetMapping("/{id}")
    @RequirePermission(Permissions.AI_CHAT)
    public ResponseEntity<byte[]> read(@PathVariable String id, AuthorizedPrincipal principal) {
        Optional<byte[]> bytes = store.read(principal.accountId(), id);
        if (bytes.isEmpty()) {
            // 过期或不属于本账号，一律 404——不区分两者，避免探测他人附件是否存在。
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                .body(bytes.get());
    }
}
