package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
public class StopLimitOrderTest {

    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private List<Order> orders;
    @Autowired
    Matcher matcher;
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;

    @BeforeEach
    void setupOrderBook() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("ABC").build();
        securityRepository.addSecurity(security);

        shareholder = Shareholder.builder().shareholderId(1).build();
        shareholder.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder);

        broker = Broker.builder().brokerId(0).credit(1_000_000L).build();
        brokerRepository.addBroker(broker);

        orders = Arrays.asList(
                new Order(1, security, BUY, 304, 15700, broker, shareholder),
                new Order(2, security, BUY, 43, 15500, broker, shareholder),
                new Order(3, security, BUY, 445, 15450, broker, shareholder),
                new Order(4, security, BUY, 526, 15450, broker, shareholder),
                new Order(5, security, BUY, 1000, 15400, broker, shareholder),
                new Order(6, security, Side.SELL, 350, 15800, broker, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        security.updateMarketPrice(20000);
    }


    // (Given - When - Then) pattern
    @Test
    void securityWithNonEmptyOrderBook_aStopLimitOrderArrivedWithLowerStopPriceThanMarket_itGoesToInactiveOrderBook(){

        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), BUY, 10, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 16000);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getInactiveOrderBook().getBuyQueue().size()).isEqualTo(1);
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 11));

    }

    @Test
    void securityWithInactiveStopLimitOrder_newTradeCauseToChangeMarketPrice_activeStopLimitAndQueued(){
        // Stop Limit Order Arrived
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), BUY, 10, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15800);
        orderHandler.handleEnterOrder(stopLimitOrderRq);
        assertThat(security.getInactiveOrderBook().getBuyQueue().size()).isEqualTo(1);

        // New Order Change The Market Price
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), BUY, 10, 15800, broker.getBrokerId(), shareholder.getShareholderId(), 0 , 0);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getMarketPrice()).isEqualTo(enterOrderRq.getPrice());
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(6);
        assertThat(security.getOrderBook().getBuyQueue().getLast().getOrderId()).isEqualTo(11);



    }

    @Test
    void securityExist_invalidStopLimitWithNonZeroPeakSizeArrive_publishErrorInMessageQueue(){
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), BUY, 10, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 1, 0, 16000);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(11);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.STOP_LIMIT_ORDER_PEAK_SIZE_NOT_ZERO
        );

    }

    @Test
    void securityExist_invalidStopLimitWithNonZeroMEQValueArrive_publishErrorInMessageQueue(){
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), BUY, 10, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 10, 16000);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(11);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.STOP_LIMIT_ORDER_MEQ_NOT_ZERO
        );

    }

    @Test
    void securityExist_invalidStopLimitWithWrongStopPriceValueArrived_publishErrorInMessageQueue() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), BUY, 10, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, -1030);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(11);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.STOP_PRICE_NOT_POSITIVE
        );

    }

    @Test
    void securityExist_newStopLimitOrderArrive_rejectForNotEnoughCredit(){
        Broker poorBroker = Broker.builder().brokerId(1).credit(100).build();
        brokerRepository.addBroker(poorBroker);
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), BUY, 10, 15000, poorBroker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 16000);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(11);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.BUYER_HAS_NOT_ENOUGH_CREDIT
        );

    }

    @Test
    void securityExist_newStopLimitOrderArrive_rejectForNotEnoughPositions(){
        Shareholder poorShareholder = Shareholder.builder().shareholderId(2).build();
        poorShareholder.incPosition(security, 50);
        shareholderRepository.addShareholder(poorShareholder);
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), SELL, 100, 15000, broker.getBrokerId(), poorShareholder.getShareholderId(), 0, 0, 14000);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(11);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.SELLER_HAS_NOT_ENOUGH_POSITIONS
        );

    }

    @Test
    void securityExistWIthNonEmptyOrderBook_aStopLimitArrivedWithLowerStopPriceThanMarketPrice_instantlyStopLimitActived(){
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), BUY, 10, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 21000);
        orderHandler.handleEnterOrder(enterOrderRq);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(6);

    }

    @Test
    void givenOrdersWithSamePrice_whenActivatedBasedOnEntryTime_thenOrdersAreProcessedAccordingToEntryTime(){

        EnterOrderRq stopLimitOrder1 = EnterOrderRq.createNewOrderRq(
                1, security.getIsin(), 13, LocalDateTime.now(), BUY, 10, 15000,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 16000);

        EnterOrderRq stopLimitOrder2 = EnterOrderRq.createNewOrderRq(
                2, security.getIsin(), 11, LocalDateTime.now(), BUY, 10, 15000,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 16000);

        EnterOrderRq stopLimitOrder3 = EnterOrderRq.createNewOrderRq(
                3, security.getIsin(), 12, LocalDateTime.now(), BUY, 10, 15000,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 16000);
        orderHandler.handleEnterOrder(stopLimitOrder1);
        orderHandler.handleEnterOrder(stopLimitOrder2);
        orderHandler.handleEnterOrder(stopLimitOrder3);


        // Then
        assertThat(security.getInactiveOrderBook().getBuyQueue().size()).isEqualTo(3);
        assertThat(security.getInactiveOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(13);
        assertThat(security.getInactiveOrderBook().getBuyQueue().getLast().getOrderId()).isEqualTo(12);


    }

    @Test
    void someInactiveStopLimitOrderExist_updateStopLimitOrderRequestArrive_FailToUpdateNotEnoughCredit(){

        List<StopLimitOrder> stopLimitOrders = Arrays.asList(
                new StopLimitOrder(11, security, BUY, 10, 15000, broker, shareholder, LocalDateTime.now(), OrderStatus.INACTIVE, 16000),
                new StopLimitOrder(12, security, BUY, 10, 15000, broker, shareholder, LocalDateTime.now(), OrderStatus.INACTIVE, 16000),
                new StopLimitOrder(13, security, BUY, 10, 15000, broker, shareholder, LocalDateTime.now(), OrderStatus.INACTIVE, 16000)
        );

        stopLimitOrders.forEach(order -> security.getInactiveOrderBook().enqueue(order));

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(4, security.getIsin(), 12, LocalDateTime.now(), BUY, 100, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 16000);

        orderHandler.handleEnterOrder(updateOrderRq);

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getRequestId()).isEqualTo(4);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.BUYER_HAS_NOT_ENOUGH_CREDIT
        );
    }

    @Test
    void someInactiveStopLimitOrderExist_updateStopLimitOrderRequestArrive_FailToUpdateNotEnoughPositions(){

        List<StopLimitOrder> stopLimitOrders = Arrays.asList(
                new StopLimitOrder(11, security, SELL, 10, 15000, broker, shareholder, LocalDateTime.now(), OrderStatus.INACTIVE, 16000),
                new StopLimitOrder(12, security, SELL, 10, 15000, broker, shareholder, LocalDateTime.now(), OrderStatus.INACTIVE, 16000),
                new StopLimitOrder(13, security, SELL, 10, 15000, broker, shareholder, LocalDateTime.now(), OrderStatus.INACTIVE, 16000)
        );

        stopLimitOrders.forEach(order -> security.getInactiveOrderBook().enqueue(order));

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(4, security.getIsin(), 12, LocalDateTime.now(), SELL, 100_000, 15000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 16000);

        orderHandler.handleEnterOrder(updateOrderRq);

        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getRequestId()).isEqualTo(4);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.SELLER_HAS_NOT_ENOUGH_POSITIONS
        );
    }

}
