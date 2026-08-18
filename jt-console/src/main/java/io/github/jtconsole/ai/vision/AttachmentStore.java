package io.github.jtconsole.ai.vision;

import io.github.jtconsole.config.ConsoleProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对话里用户上传图片的落盘存储。
 *
 * <p><b>为什么落盘而不是入库</b>：一张手机照片几 MB，base64 塞进 {@code ai_message} 会让
 * {@code findMessages} 的响应体直接爆掉——那正是 {@code tool_trace} 已经踩过并因此加了体积上限
 * 的坑。库里只留一个引用。
 *
 * <p><b>为什么不建表</b>：归属关系由目录结构表达（{@code <root>/<账号号>/<uuid>.jpg}），
 * 再建一张表只会多一处需要和目录保持一致的状态，而两处状态迟早会分叉。
 *
 * <p><b>为什么按账号而不是按会话分目录</b>：图片要在「发送消息」之前就上传完（用户先贴图、
 * 再打字），而新开的对话此刻还没有会话号。按账号分目录既避开了这个时序问题，也正好等于真正的
 * 访问边界——能看这张图的就是上传它的那个账号。
 *
 * <p><b>文件名一律是新生成的 UUID</b>，绝不采用上传时的原名：原名是外部输入，
 * 拿它拼路径就是目录穿越。账号号取自登录态解析出的长整型，同样不可能带出 {@code ..}。
 */
@Component
public class AttachmentStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentStore.class);

    /** 落盘一律是 JPEG：{@link ImagePreparer} 已经统一了格式，扩展名跟着它走。 */
    private static final String EXTENSION = ".jpg";

    private final Path root;
    private final long maxBytes;
    private final ConsoleProperties.Ai.Vision vision;

    public AttachmentStore(ConsoleProperties properties) {
        ConsoleProperties.Ai ai = properties.getAi();
        this.root = ai.getAttachment().getDirectory().toAbsolutePath().normalize();
        this.maxBytes = ai.getAttachment().getMaxSize().toBytes();
        this.vision = ai.getVision();
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * 保存一张上传的图片。
     *
     * <p>保存的是**预处理之后**的字节，不是原始上传内容：一是省磁盘，二是让「库里存的」与
     * 「送去识别的」是同一份数据——否则用户看到的图和模型看到的图可能不是一回事，出了偏差
     * 无从对账。
     *
     * @return 附件 id（UUID 字符串），用于后续引用
     */
    public String save(long accountId, byte[] raw, String declaredMimeType) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("上传内容为空");
        }
        if (raw.length > maxBytes) {
            throw new IllegalArgumentException(
                    "图片超过 " + (maxBytes / 1024 / 1024) + "MB 上限");
        }
        String mime = declaredMimeType == null ? "" : declaredMimeType.toLowerCase(Locale.ROOT).trim();
        if (!VisionImage.ALLOWED_MIME_TYPES.contains(mime)) {
            throw new IllegalArgumentException("只支持 JPG、PNG、WebP 图片");
        }
        // 解码一次既完成缩放，也顺带证明它真的是一张图——仅凭 Content-Type 判断等于没判断。
        VisionImage prepared = ImagePreparer.prepare(raw, vision.getMaxEdgePixels(), null);

        String id = UUID.randomUUID().toString().replace("-", "");
        Path directory = accountDirectory(accountId);
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(id + EXTENSION), prepared.bytes());
        } catch (IOException failure) {
            throw new UncheckedIOException("附件写入失败", failure);
        }
        LOGGER.debug("账号 {} 保存附件 {}（{} 字节）", accountId, id, prepared.bytes().length);
        return id;
    }

    /** 读取附件字节。文件不存在时返回空。 */
    public java.util.Optional<byte[]> read(long accountId, String attachmentId) {
        Path file = resolve(accountId, attachmentId);
        if (file == null || !Files.isRegularFile(file)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Files.readAllBytes(file));
        } catch (IOException failure) {
            LOGGER.warn("附件读取失败 {}/{}：{}", accountId, attachmentId, failure.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 清理超过保留期的附件目录。
     *
     * <p>按目录的最后修改时间判断，不去和数据库对账：对账要么锁库要么容忍竞态，而附件本就是
     * 对话的输入而非结论——描述已经落在消息里，原图过期删掉不损失信息。
     *
     * @return 清理掉的账号目录数
     */
    public int purgeOlderThan(Instant cutoff) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> directories = Files.list(root)) {
            List<Path> candidates = directories.filter(Files::isDirectory).toList();
            for (Path directory : candidates) {
                try {
                    if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                        deleteRecursively(directory);
                        removed++;
                    }
                } catch (IOException skip) {
                    LOGGER.debug("跳过附件目录 {}：{}", directory, skip.getMessage());
                }
            }
        } catch (IOException failure) {
            LOGGER.warn("附件清理失败：{}", failure.getMessage());
        }
        return removed;
    }

    private Path accountDirectory(long accountId) {
        return root.resolve(Long.toString(accountId));
    }

    /**
     * 解析附件路径。
     *
     * <p>id 必须是 32 位十六进制——即 {@link UUID} 去掉连字符的形态。这一条不是格式洁癖，
     * 它是防目录穿越的那道闸：只要不满足就直接返回 null，绝不把外部字符串拼进路径。
     */
    private Path resolve(long accountId, String attachmentId) {
        if (attachmentId == null || !attachmentId.matches("[0-9a-f]{32}")) {
            return null;
        }
        Path file = accountDirectory(accountId).resolve(attachmentId + EXTENSION);
        // 双保险：规范化后必须仍在根目录之下。
        return file.normalize().startsWith(root) ? file : null;
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    LOGGER.debug("附件删除失败：{}", path);
                }
            });
        } catch (IOException failure) {
            LOGGER.warn("附件目录删除失败 {}：{}", directory, failure.getMessage());
        }
    }
}
