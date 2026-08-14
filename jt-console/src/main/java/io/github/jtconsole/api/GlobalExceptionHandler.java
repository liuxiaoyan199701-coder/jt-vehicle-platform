package io.github.jtconsole.api;

import io.github.jtconsole.audit.AuditContext;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.FleetBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务接口的统一异常出口，只作用于 {@code io.github.jtconsole.web} 下的控制器。
 *
 * <p>{@code IngestController} 被有意排除在外：那条链路的错误语义完全相反——存储异常必须
 * 真正变成 5xx，网关才会重试；包装成 200 + 错误码会让消息被静默丢弃。
 */
@RestControllerAdvice(basePackages = "io.github.jtconsole.web")
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FleetBusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleFleetBusiness(FleetBusinessException failure) {
        LOGGER.debug("Rejected fleet request: {}", failure.code());
        AuditContext.businessCode(failure.code());
        return ApiResponse.error(failure.code(), failure.getMessage());
    }

    @ExceptionHandler(IamException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleIam(IamException failure) {
        LOGGER.debug("Rejected identity or tenancy request: {}", failure.code());
        AuditContext.businessCode(failure.code());
        return ApiResponse.error(failure.code(), failure.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException failure) {
        LOGGER.debug("Rejected invalid console request: {}", failure.getClass().getSimpleName());
        AuditContext.businessCode("4000");
        return ApiResponse.error("4000", "请求参数错误");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleUnexpected(Exception failure) {
        LOGGER.error("Unhandled error in console API: {}", failure.getClass().getSimpleName());
        AuditContext.businessCode("5000");
        return ApiResponse.error("5000", "服务器内部错误");
    }
}
