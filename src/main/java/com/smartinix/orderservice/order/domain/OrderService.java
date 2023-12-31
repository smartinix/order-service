package com.smartinix.orderservice.order.domain;


import com.smartinix.orderservice.book.Book;
import com.smartinix.orderservice.book.BookClient;
import com.smartinix.orderservice.order.event.OrderAcceptedMessage;
import com.smartinix.orderservice.order.event.OrderDispatchedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class OrderService {
    private static final Logger log =
        LoggerFactory.getLogger(OrderService.class);

    private final BookClient bookClient;
    private final OrderRepository orderRepository;
    private final StreamBridge streamBridge;


    public OrderService(
        BookClient bookClient,
        StreamBridge streamBridge, OrderRepository orderRepository
    ) {
        this.bookClient = bookClient;
        this.orderRepository = orderRepository;
        this.streamBridge = streamBridge;
    }

    public static Order buildAcceptedOrder(Book book, int quantity) {
        log.info("Build ACCEPTED order for book with ISBN {}, quantity {}.", book.isbn(), quantity);
        return Order.of(book.isbn(), book.title() + " - " + book.author(),
                        book.price(), quantity, OrderStatus.ACCEPTED);
    }

    public static Order buildRejectedOrder(
        String bookIsbn, int quantity
    ) {
        log.info("Build REJECTED order for book with ISBN {}, quantity {}.", bookIsbn, quantity);
        return Order.of(bookIsbn, null, null, quantity, OrderStatus.REJECTED);
    }

    public Flux<Order> getAllOrders(String userId) {
        return orderRepository.findAllByCreatedBy(userId);
    }

    @Transactional
    public Mono<Order> submitOrder(String isbn, int quantity) {
        log.info("Submit order for ISBN {}, quantity {}.", isbn, quantity);
        return bookClient.getBookByIsbn(isbn)
            .map(book -> buildAcceptedOrder(book, quantity))
            .defaultIfEmpty(
                buildRejectedOrder(isbn, quantity)
            )
            .flatMap(orderRepository::save)
            .doOnNext(this::publishOrderAcceptedEvent);
    }

    public Flux<Order> consumeOrderDispatchedEvent(
        Flux<OrderDispatchedMessage> flux
    ) {
        log.info("Consume order DISPATCHED event.");
        return flux
            .flatMap(message ->
                         orderRepository.findById(message.orderId()))
            .map(this::buildDispatchedOrder)
            .flatMap(orderRepository::save);
    }

    private Order buildDispatchedOrder(Order existingOrder) {
        return new Order(
            existingOrder.id(),
            existingOrder.bookIsbn(),
            existingOrder.bookName(),
            existingOrder.bookPrice(),
            existingOrder.quantity(),
            OrderStatus.DISPATCHED,
            existingOrder.createdDate(),
            existingOrder.lastModifiedDate(),
            existingOrder.createdBy(),
            existingOrder.lastModifiedBy(),
            existingOrder.version()
        );
    }

    private void publishOrderAcceptedEvent(Order order) {
        log.info("Publish order ACCEPTED event.");
        if (!order.status().equals(OrderStatus.ACCEPTED)) {
            log.info("Order is not ACCEPTED.");
            return;
        }
        var orderAcceptedMessage =
            new OrderAcceptedMessage(order.id());
        log.info("Sending order accepted event with id: {}", order.id());
        var result = streamBridge.send("acceptOrder-out-0",
                                       orderAcceptedMessage);
        log.info("Result of sending data for order with id {}: {}",
                 order.id(), result);
    }
}
