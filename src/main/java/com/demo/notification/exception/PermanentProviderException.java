package com.demo.notification.exception;

import com.demo.notification.domain.types.ErrorCategory;
import lombok.Getter;

@Getter
public class PermanentProviderException extends RuntimeException {

    private final int statusCode;
    private final ErrorCategory errorCategory;
    private final String providerName;

    public PermanentProviderException(String message, int statusCode, ErrorCategory errorCategory, String providerName) {
        super(message);
        this.statusCode = statusCode;
        this.errorCategory = errorCategory;
        this.providerName = providerName;
    }
}

