package com.demo.notification.channel;

import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryResponse {
    private boolean successful;
    private DeliveryStatus status;
    private String providerResponseCode;
    private String errorMessage;
    private Long executionTimeMs;
    private ErrorCategory errorCategory;
}

