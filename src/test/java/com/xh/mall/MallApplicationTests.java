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

}
