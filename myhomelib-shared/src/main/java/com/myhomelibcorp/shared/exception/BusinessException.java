package com.myhomelibcorp.shared.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String userMessage;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.userMessage = errorCode.getDefaultMessage();
    }

    public BusinessException(ErrorCode errorCode, String userMessage) {
        super(userMessage);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.userMessage = errorCode.getDefaultMessage();
    }

    public BusinessException(ErrorCode errorCode, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }
}