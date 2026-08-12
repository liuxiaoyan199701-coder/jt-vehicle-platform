package io.github.jtconsole.api;

/**
 * 统一响应包装。格式对齐 soybean-admin 前端的默认约定：它按
 * {@code code === '0000'} 判定业务成功，否则弹出 {@code msg}。
 */
public record ApiResponse<T>(String code, String msg, T data) {

    public static final String SUCCESS_CODE = "0000";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "ok", data);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
