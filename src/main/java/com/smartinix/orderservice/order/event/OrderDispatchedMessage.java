package com.smartinix.orderservice.order.event;

public record OrderDispatchedMessage(
    Long orderId
) {
}
