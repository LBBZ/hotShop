package com.real.common.handler;


import com.real.common.exception.InventoryShortageException;
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
