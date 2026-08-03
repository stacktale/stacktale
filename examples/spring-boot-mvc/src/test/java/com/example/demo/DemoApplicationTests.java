package com.example.demo;

import com.example.demo.exception.OrderConfirmationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DemoApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void confirmOrderThrowsOrderConfirmationExceptionOnCacheMiss() throws Exception {
        mockMvc.perform(post("/orders/123/confirm"))
                .andExpect(status().isInternalServerError())
                .andExpect(result -> assertInstanceOf(OrderConfirmationException.class, result.getResolvedException()));
    }
}
