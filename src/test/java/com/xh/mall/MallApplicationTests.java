package com.xh.mall;

import com.xh.mall.entity.Order;
import com.xh.mall.entity.Product;
import com.xh.mall.mapper.OrderMapper;
import com.xh.mall.mapper.ProductMapper;
import com.xh.mall.service.OrderService;
import com.xh.mall.service.ProductService;
import com.xh.mall.util.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.xh.mall.util.test.buildTestOrder.buildTestOrder;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class MallApplicationTests {

    @Autowired
    ProductMapper productMapper;
    @Autowired
    ProductService productService;
    @Autowired
    OrderService orderService;
    @Autowired
    OrderMapper orderMapper;

    @Test
    void contextLoads() {

        Product product = productService.getProductById(200L);
        System.out.println(product);
    }

    @Test
    void itemId() {
        Order order2 = orderMapper.selectOrderById("order_123");
        order2.setStatus(OrderStatus.CANCELED);
        orderMapper.updateOrder(order2);
    }

/*    @Test
    void testRetryLogic() {
        Order order = buildTestOrder();
        // 模拟库存冲突（第一次失败，第二次成功）
        when(productMapper.reduceStock(any(), any()))
                .thenReturn(0)  // 第一次返回 0（失败）
                .thenReturn(1); // 第二次返回 1（成功）

        String orderId = orderService.createOrder(order);
        assertNotNull(orderId);
    }*/

}
