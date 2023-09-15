package com.smartinix.orderservice.order.event;

public record OrderAcceptedMessage(
    Long orderId
) {
}
