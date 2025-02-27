package com.xh.mall.handler;

import com.xh.mall.util.exception.InventoryShortageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InventoryShortageException.class)
    public ResponseEntity<String> handleInventoryShortage(InventoryShortageException e) {
        return ResponseEntity.status(409).body(e.getMessage()); // HTTP 409 Conflict
    }

}
