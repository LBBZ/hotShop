package com.real.admin;

import com.real.admin.controller.AdminProductController;
import com.real.admin.service.AdminProductAuditService;
import com.real.admin.service.AdminOperationsService;
import com.real.domain.service.ProductService;
import com.real.domain.entity.Product;
import com.real.security.entity.CustomUserDetails;
import com.real.common.api.ApiException;
import com.real.common.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.*;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminInventoryApiTest {
    private AdminProductAuditService audit;
    private MockMvc mvc;
    @BeforeEach void setup() {
        audit=mock(AdminProductAuditService.class);
        mvc=MockMvcBuilders.standaloneSetup(new AdminProductController(mock(ProductService.class),audit,mock(AdminOperationsService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter p) { return p.getParameterType()==CustomUserDetails.class; }
                public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest w, WebDataBinderFactory f) {
                    return CustomUserDetails.builder().userId(7L).build();
                }
            }).build();
    }
    private Product product() {
        Product p=new Product(1L,"renamed",new BigDecimal("10.00"),99,"test","test",LocalDateTime.now());
        p.setVersion(1L); return p;
    }
    @Test void metadataRequestOmitsStockAndResponseReturnsCurrentVersion() throws Exception {
        when(audit.update(eq(1L),any(),eq(7L),eq("rename only"),any())).thenReturn(product());
        mvc.perform(put("/admin/api/v1/products/1").contentType("application/json")
                .content("""
                    {"name":"renamed","price":"10.00","category":"test","description":"test","reason":"rename only"}
                    """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(99))
                .andExpect(jsonPath("$.version").value("1"));
        var capture=ArgumentCaptor.forClass(Product.class);
        verify(audit).update(eq(1L),capture.capture(),eq(7L),eq("rename only"),any());
        assertThat(capture.getValue().getStock()).isNull();
    }
    @Test void staleLegacyStockCannotEnterMetadataMutation() throws Exception {
        when(audit.update(eq(1L),any(),eq(7L),eq("rename only"),any())).thenReturn(product());
        mvc.perform(put("/admin/api/v1/products/1").contentType("application/json")
                .content("""
                    {"name":"renamed","price":"10.00","stock":100,"category":"test","reason":"rename only"}
                    """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(99));
        var capture=ArgumentCaptor.forClass(Product.class);
        verify(audit).update(eq(1L),capture.capture(),eq(7L),eq("rename only"),any());
        assertThat(capture.getValue().getStock()).isNull();
    }
    @Test void adjustmentConflictIsAnUnderstandable409() throws Exception {
        when(audit.adjustStock(eq(1L),eq(5),eq(0L),eq(7L),eq("restock goods"),any()))
                .thenThrow(ApiException.conflict("STOCK_ADJUSTMENT_CONFLICT","库存已变化，请刷新当前库存和版本后重试。"));
        mvc.perform(post("/admin/api/v1/products/1/stock-adjustments").contentType("application/json")
                .content("""
                    {"delta":5,"expectedVersion":"0","reason":"restock goods"}
                    """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_ADJUSTMENT_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("库存已变化，请刷新当前库存和版本后重试。"));
    }
}
