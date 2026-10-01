package com.example.demo.controller;

import com.example.demo.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders/{id}/confirm")
    public ResponseEntity<Void> confirmOrder(@PathVariable int id) {
        orderService.confirmOrder(id);
        return ResponseEntity.ok().build();
    }
}
