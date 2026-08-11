package com.real.admin.service;

import com.real.common.api.ApiException;
import com.real.common.api.RequestContext;
import com.real.common.audit.AuditAction;
import com.real.common.audit.AuditActor;
import com.real.common.audit.AuditActorType;
import com.real.common.audit.AuditEvent;
import com.real.common.audit.AuditResource;
import com.real.common.audit.AuditResourceType;
import com.real.common.audit.AuditResult;
import com.real.common.audit.AuditSource;
import com.real.common.audit.FlashSaleActivityLoadAuditState;
import com.real.common.audit.OperationFailureAuditState;
import com.real.common.audit.AdminFlashSaleActivityLoadAuditState;
import com.real.common.audit.AdminOperationFailureAuditState;
import com.real.domain.service.seckill.FlashSaleActivityLoader;
import com.real.domain.service.seckill.FlashSaleLoadResult;
import com.real.security.audit.AuditLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AdminFlashSaleActivityLoadService {
    private final FlashSaleActivityLoader loader;
    private final AuditLogWriter auditLogWriter;

    public AdminFlashSaleActivityLoadService(
            FlashSaleActivityLoader loader,
            AuditLogWriter auditLogWriter
    ) {
        this.loader = loader;
        this.auditLogWriter = auditLogWriter;
    }

    public FlashSaleLoadResult load(
            long activityId,
            long administratorId,
            String reason,
            HttpServletRequest request
    ) {
        try {
            FlashSaleLoadResult result = loader.load(activityId);
            if (result.code() != com.real.domain.service.seckill.FlashSaleLoadCode.LOADED
                    && result.code() != com.real.domain.service.seckill.FlashSaleLoadCode.IDEMPOTENT) {
                ApiException problem = problem(result);
                appendFailure(administratorId, activityId, problem.getCode(), reason, request);
                throw problem;
            }
            auditLogWriter.append(event(
                    administratorId,
                    activityId,
                    AuditResult.SUCCESS,
                    new AdminFlashSaleActivityLoadAuditState(
                            result.code().name(),
                            result.databaseVersion(),
                            result.redisVersion(),
                            result.databaseAvailableStock(),
                            result.redisAvailableStock(),
                            result.streamEventCount(),
                            result.reservationRecordCount(),
                            result.consistent(),
                            reason
                    ),
                    request
            ));
            return result;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            appendFailure(administratorId, activityId, "OPERATION_FAILED", reason, request);
            throw exception;
        }
    }

    private ApiException problem(FlashSaleLoadResult result) {
        return switch (result.code()) {
            case ACTIVITY_NOT_FOUND -> new ApiException(
                    HttpStatus.NOT_FOUND,
                    "FLASH_SALE_ACTIVITY_NOT_FOUND",
                    "Resource not found",
                    result.detail()
            );
            case ACTIVITY_INVALID -> ApiException.conflict(
                    "FLASH_SALE_ACTIVITY_INVALID",
                    result.detail()
            );
            case STALE_VERSION -> ApiException.conflict(
                    "FLASH_SALE_STALE_VERSION",
                    result.detail()
            );
            case RESERVATIONS_EXIST -> ApiException.conflict(
                    "FLASH_SALE_RESERVATIONS_EXIST",
                    result.detail()
            );
            case INTERNAL_STATE_INVALID -> ApiException.serviceUnavailable(
                    "SECKILL_STATE_INVALID",
                    "Flash-sale state is temporarily unavailable"
            );
            case LOADED, IDEMPOTENT ->
                    throw new IllegalArgumentException("Successful load cannot be mapped to a problem");
        };
    }

    private void appendFailure(
            long administratorId,
            long activityId,
            String reasonCode,
            String administratorReason,
            HttpServletRequest request
    ) {
        auditLogWriter.appendFailure(event(
                administratorId,
                activityId,
                AuditResult.FAILURE,
                new AdminOperationFailureAuditState(reasonCode, administratorReason),
                request
        ));
    }

    private AuditEvent event(
            long administratorId,
            long activityId,
            AuditResult result,
            com.real.common.audit.AuditStateSummary state,
            HttpServletRequest request
    ) {
        return new AuditEvent(
                AuditActor.identified(AuditActorType.ADMIN, administratorId),
                null,
                AuditAction.FLASH_SALE_ACTIVITY_LOADED,
                AuditResource.identified(AuditResourceType.FLASH_SALE_ACTIVITY, activityId),
                result,
                RequestContext.requestId(request),
                RequestContext.traceId(request),
                AuditSource.ADMIN_API,
                Instant.now(),
                state
        );
    }
}
