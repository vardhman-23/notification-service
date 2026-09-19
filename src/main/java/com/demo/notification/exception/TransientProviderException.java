package com.demo.notification.exception;

import com.demo.notification.domain.types.ErrorCategory;
import lombok.Getter;

@Getter
public class TransientProviderException extends RuntimeException {

    private final int statusCode;
    private final ErrorCategory errorCategory;
    private final String providerName;

    public TransientProviderException(String message, int statusCode, ErrorCategory errorCategory, String providerName) {
        super(message);
        this.statusCode = statusCode;
        this.errorCategory = errorCategory;
        this.providerName = providerName;
    }

    public TransientProviderException(String message, Throwable cause, int statusCode, ErrorCategory errorCategory, String providerName) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCategory = errorCategory;
        this.providerName = providerName;
    }
}

