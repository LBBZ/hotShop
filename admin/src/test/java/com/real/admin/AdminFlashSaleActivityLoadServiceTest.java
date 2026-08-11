package com.real.admin;

import com.real.admin.service.AdminFlashSaleActivityLoadService;
import com.real.common.api.RequestContext;
import com.real.common.audit.AuditAction;
import com.real.common.audit.AuditEvent;
import com.real.common.audit.AuditResourceType;
import com.real.common.audit.AuditResult;
import com.real.domain.service.seckill.FlashSaleActivityLoader;
import com.real.domain.service.seckill.FlashSaleLoadCode;
import com.real.domain.service.seckill.FlashSaleLoadResult;
import com.real.security.audit.AuditLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminFlashSaleActivityLoadServiceTest {
    private FlashSaleActivityLoader loader;
    private AuditLogWriter writer;
    private AdminFlashSaleActivityLoadService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        loader = mock(FlashSaleActivityLoader.class);
        writer = mock(AuditLogWriter.class);
        service = new AdminFlashSaleActivityLoadService(loader, writer);
        request = new MockHttpServletRequest();
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, "task07-load-request");
        request.setAttribute(
                RequestContext.TRACE_ID_ATTRIBUTE,
                "0123456789abcdef0123456789abcdef"
        );
    }

    @Test
    void successfulLoadAppendsAdministratorAuditWithReconciliation() {
        when(loader.load(7001L)).thenReturn(new FlashSaleLoadResult(
                FlashSaleLoadCode.LOADED,
                7001L,
                2,
                2,
                10,
                8,
                2,
                2,
                2,
                true,
                "loaded"
        ));

        FlashSaleLoadResult result = service.load(7001L, 99L, "scheduled campaign load", request);

        assertThat(result.consistent()).isTrue();
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(writer).append(event.capture());
        assertThat(event.getValue().actor().id()).isEqualTo("99");
        assertThat(event.getValue().action()).isEqualTo(AuditAction.FLASH_SALE_ACTIVITY_LOADED);
        assertThat(event.getValue().resource().type())
                .isEqualTo(AuditResourceType.FLASH_SALE_ACTIVITY);
        assertThat(event.getValue().resource().id()).isEqualTo("7001");
        assertThat(event.getValue().result()).isEqualTo(AuditResult.SUCCESS);
    }

    @Test
    void invalidActivityWindowOrStockIsRejectedAndFailureIsAudited() {
        when(loader.load(7002L)).thenReturn(new FlashSaleLoadResult(
                FlashSaleLoadCode.ACTIVITY_INVALID,
                7002L,
                1,
                null,
                -1,
                null,
                0,
                0,
                0,
                false,
                "Activity time window or inventory facts are invalid"
        ));

        assertThatThrownBy(() -> service.load(
                7002L, 99L, "validate scheduled activity facts", request))
                .hasMessageContaining("Activity time window or inventory facts are invalid");
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(writer).appendFailure(event.capture());
        assertThat(event.getValue().result()).isEqualTo(AuditResult.FAILURE);
        assertThat(event.getValue().resource().id()).isEqualTo("7002");
    }
}
