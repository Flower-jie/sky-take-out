package com.sky;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.entity.User;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.impl.OrderServiceImpl;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderSubmitVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderSafetyTest {
    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 101L;
    private static final String ORDER_NUMBER = "local-order-101";
    private static final BigDecimal AMOUNT = new BigDecimal("84.35");

    @Mock private OrderMapper orderMapper;
    @Mock private OrderDetailMapper orderDetailMapper;
    @Mock private AddressBookMapper addressBookMapper;
    @Mock private ShoppingCartMapper shoppingCartMapper;
    @Mock private UserMapper userMapper;
    @Mock private WeChatPayUtil weChatPayUtil;
    @InjectMocks private OrderServiceImpl service;

    enum Cancellation { USER, REJECTION, ADMIN }

    @BeforeEach
    void login() {
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void clearLogin() {
        BaseContext.removeCurrentId();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -100, 999})
    void submitAndPaymentUseCartUnitPricesTimesQuantityNotClientAmounts(Integer clientPackAmount) throws Exception {
        OrdersSubmitDTO dto = submitDto();
        dto.setAmount(new BigDecimal("0.01"));
        dto.setPackAmount(clientPackAmount);
        List<ShoppingCart> carts = Arrays.asList(cart("12.30", 2), cart("0.10", 3), cart("45.67", 1));
        when(addressBookMapper.getById(5L)).thenReturn(new AddressBook());
        when(shoppingCartMapper.list(any(ShoppingCart.class))).thenReturn(carts);
        doAnswer(invocation -> {
            invocation.<Orders>getArgument(0).setId(ORDER_ID);
            return null;
        }).when(orderMapper).insert(any(Orders.class));

        // No Spring context or network: all three map HTTP requests are intercepted.
        try (MockedStatic<HttpClientUtil> http = mockStatic(HttpClientUtil.class)) {
            http.when(() -> HttpClientUtil.doGet(eq("https://api.map.baidu.com/geocoding/v3"), anyMap()))
                    .thenReturn("{\"status\":0,\"result\":{\"location\":{\"lat\":1,\"lng\":2}}}");
            http.when(() -> HttpClientUtil.doGet(eq("https://api.map.baidu.com/directionlite/v1/driving"), anyMap()))
                    .thenReturn("{\"status\":0,\"result\":{\"routes\":[{\"distance\":100}]}}");
            OrderSubmitVO result = service.submit(dto);
            assertEquals(new BigDecimal("70.57"), result.getOrderAmount());
            http.verify(() -> HttpClientUtil.doGet(eq("https://api.map.baidu.com/geocoding/v3"), anyMap()), times(2));
            http.verify(() -> HttpClientUtil.doGet(eq("https://api.map.baidu.com/directionlite/v1/driving"), anyMap()));
            http.verifyNoMoreInteractions();
        }

        ArgumentCaptor<Orders> saved = ArgumentCaptor.forClass(Orders.class);
        verify(orderMapper).insert(saved.capture());
        Orders order = saved.getValue();
        assertEquals(new BigDecimal("70.57"), order.getAmount());
        assertEquals(0, order.getPackAmount());
        assertEquals(USER_ID, order.getUserId());
        assertEquals(Orders.PENDING_PAYMENT, order.getStatus());
        assertEquals(Orders.UN_PAID, order.getPayStatus());
        verify(shoppingCartMapper).list(argThat(query -> USER_ID.equals(query.getUserId())));
        verify(shoppingCartMapper).deleteByUserId(USER_ID);
        verify(orderDetailMapper).insertBatch(argThat(details -> details.size() == 3
                && details.stream().allMatch(detail -> ORDER_ID.equals(detail.getOrderId()))
                && sumDetails(details).compareTo(order.getAmount()) == 0));

        when(orderMapper.getByNumber(order.getNumber())).thenReturn(order);
        when(userMapper.getById(USER_ID)).thenReturn(User.builder().openid("mock-openid").build());
        when(weChatPayUtil.pay(order.getNumber(), order.getAmount(), "苍穹外卖订单", "mock-openid"))
                .thenReturn(JSON.parseObject("{\"package\":\"prepay_id=mock\"}"));
        OrdersPaymentDTO payment = new OrdersPaymentDTO();
        payment.setOrderNumber(order.getNumber());
        assertEquals("prepay_id=mock", service.payment(payment).getPackageStr());
        verify(weChatPayUtil).pay(order.getNumber(), new BigDecimal("70.57"), "苍穹外卖订单", "mock-openid");
    }

    @ParameterizedTest
    @MethodSource("invalidCartLines")
    void invalidCartCannotBePersistedOrTriggerHttp(ShoppingCart invalid) {
        when(addressBookMapper.getById(5L)).thenReturn(new AddressBook());
        when(shoppingCartMapper.list(any(ShoppingCart.class))).thenReturn(Collections.singletonList(invalid));
        try (MockedStatic<HttpClientUtil> http = mockStatic(HttpClientUtil.class)) {
            assertThrows(ShoppingCartBusinessException.class, () -> service.submit(submitDto()));
            http.verifyNoInteractions();
        }
        verifyNoInteractions(orderMapper, orderDetailMapper, weChatPayUtil);
        verify(shoppingCartMapper, never()).deleteByUserId(anyLong());
    }

    static Stream<ShoppingCart> invalidCartLines() {
        return Stream.of(null, cart(null, 1), cart("-1.00", 1), cart("1.00", null),
                cart("1.00", 0), cart("1.00", -1));
    }

    @Test
    void missingOrderCannotBePaid() {
        assertThrows(OrderBusinessException.class, () -> service.payment(paymentDto()));
        verifyNoInteractions(weChatPayUtil, userMapper);
        verify(orderMapper, never()).update(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {99L})
    void paymentAndUserCancellationRejectWrongOrMissingLogin(Long currentUser) {
        BaseContext.setCurrentId(currentUser);
        when(orderMapper.getByNumber(ORDER_NUMBER)).thenReturn(order(Orders.PENDING_PAYMENT, Orders.UN_PAID));
        when(orderMapper.getById(ORDER_ID)).thenReturn(order(Orders.TO_BE_CONFIRMED, Orders.PAID));
        assertThrows(OrderBusinessException.class, () -> service.payment(paymentDto()));
        assertThrows(OrderBusinessException.class, () -> service.UserCancelById(ORDER_ID));
        verifyNoInteractions(weChatPayUtil, userMapper);
        verify(orderMapper, never()).update(any());
    }

    @ParameterizedTest
    @MethodSource("invalidPaymentStates")
    void paymentRejectsPaidOrInvalidStates(Integer status, Integer payStatus) {
        when(orderMapper.getByNumber(ORDER_NUMBER)).thenReturn(order(status, payStatus));
        assertThrows(OrderBusinessException.class, () -> service.payment(paymentDto()));
        verifyNoInteractions(weChatPayUtil, userMapper);
        verify(orderMapper, never()).update(any());
    }

    static Stream<Arguments> invalidPaymentStates() {
        return Stream.concat(
                Stream.of(null, 0, 2, 3, 4, 5, 6, 7).map(status -> Arguments.of(status, Orders.UN_PAID)),
                Stream.of(null, -1, 1, 2, 3).map(payStatus -> Arguments.of(Orders.PENDING_PAYMENT, payStatus)));
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void paymentRejectsInvalidDatabaseAmount(BigDecimal amount) {
        Orders order = order(Orders.PENDING_PAYMENT, Orders.UN_PAID);
        order.setAmount(amount);
        when(orderMapper.getByNumber(ORDER_NUMBER)).thenReturn(order);
        assertThrows(OrderBusinessException.class, () -> service.payment(paymentDto()));
        verifyNoInteractions(weChatPayUtil, userMapper);
    }

    static Stream<BigDecimal> invalidAmounts() {
        return Stream.of(null, BigDecimal.ZERO, new BigDecimal("-1.00"),
                new BigDecimal("0.001"), new BigDecimal("21474836.48"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"{\"code\":\"ORDERPAID\"}", "{\"code\":\"SYSTEM_ERROR\"}"})
    void paymentErrorIsNotReportedAsSuccess(String response) throws Exception {
        when(orderMapper.getByNumber(ORDER_NUMBER)).thenReturn(order(Orders.PENDING_PAYMENT, Orders.UN_PAID));
        when(userMapper.getById(USER_ID)).thenReturn(User.builder().openid("mock-openid").build());
        when(weChatPayUtil.pay(ORDER_NUMBER, AMOUNT, "苍穹外卖订单", "mock-openid"))
                .thenReturn(JSON.parseObject(response));
        assertThrows(OrderBusinessException.class, () -> service.payment(paymentDto()));
        verify(orderMapper, never()).update(any());
    }

    @ParameterizedTest
    @MethodSource("acceptedRefunds")
    void eachPaidCancellationRefundsFullAmountAfterAcceptance(Cancellation entry, int status, String refundStatus) throws Exception {
        Orders original = order(status, Orders.PAID);
        when(orderMapper.getById(ORDER_ID)).thenReturn(original);
        when(weChatPayUtil.refund(ORDER_NUMBER, ORDER_NUMBER, AMOUNT, AMOUNT)).thenReturn(refundResponse(refundStatus));
        cancel(entry);
        Orders update = updatedOrder();
        assertEquals(Orders.REFUND, update.getPayStatus());
        assertReason(entry, update);
        verify(weChatPayUtil).refund(ORDER_NUMBER, ORDER_NUMBER, AMOUNT, AMOUNT);

        original.setStatus(update.getStatus());
        original.setPayStatus(update.getPayStatus());
        assertThrows(OrderBusinessException.class, () -> cancel(entry));
        verifyNoMoreInteractions(weChatPayUtil);
        verify(orderMapper, times(1)).update(any());
    }

    static Stream<Arguments> acceptedRefunds() {
        return allowedCancellations().flatMap(args -> Stream.of("PROCESSING", "SUCCESS")
                .map(status -> Arguments.of(args.get()[0], args.get()[1], status)));
    }

    @ParameterizedTest
    @MethodSource("allowedCancellations")
    void unpaidCancellationsDoNotRefundOrSetRefundStatus(Cancellation entry, int status) throws Exception {
        when(orderMapper.getById(ORDER_ID)).thenReturn(order(status, Orders.UN_PAID));
        cancel(entry);
        Orders update = updatedOrder();
        assertNull(update.getPayStatus());
        assertReason(entry, update);
        verifyNoInteractions(weChatPayUtil);
    }

    static Stream<Arguments> allowedCancellations() {
        return Stream.of(Arguments.of(Cancellation.USER, 1), Arguments.of(Cancellation.USER, 2),
                Arguments.of(Cancellation.REJECTION, 2), Arguments.of(Cancellation.ADMIN, 1),
                Arguments.of(Cancellation.ADMIN, 2), Arguments.of(Cancellation.ADMIN, 3),
                Arguments.of(Cancellation.ADMIN, 4));
    }

    @ParameterizedTest
    @EnumSource(Cancellation.class)
    void missingOrdersCannotBeCancelled(Cancellation entry) {
        assertThrows(OrderBusinessException.class, () -> cancel(entry));
        verifyNoInteractions(weChatPayUtil);
        verify(orderMapper, never()).update(any());
    }

    @ParameterizedTest
    @MethodSource("invalidCancellationStates")
    void invalidCancellationStatesNeverTriggerRefund(Cancellation entry, Integer status) {
        when(orderMapper.getById(ORDER_ID)).thenReturn(order(status, Orders.PAID));
        assertThrows(OrderBusinessException.class, () -> cancel(entry));
        verifyNoInteractions(weChatPayUtil);
        verify(orderMapper, never()).update(any());
    }

    static Stream<Arguments> invalidCancellationStates() {
        return Stream.of(Cancellation.values()).flatMap(entry -> Stream.of(null, -1, 0, 1, 2, 3, 4, 5, 6, 7)
                .filter(status -> status == null || status < 1 || status > 4
                        || entry == Cancellation.USER && status > 2
                        || entry == Cancellation.REJECTION && status != 2)
                .map(status -> Arguments.of(entry, status)));
    }

    @ParameterizedTest
    @MethodSource("invalidCancellationPayStates")
    void invalidOrAlreadyRefundedPaymentStatesAreRejected(Cancellation entry, Integer payStatus) {
        when(orderMapper.getById(ORDER_ID)).thenReturn(order(Orders.TO_BE_CONFIRMED, payStatus));
        assertThrows(OrderBusinessException.class, () -> cancel(entry));
        verifyNoInteractions(weChatPayUtil);
        verify(orderMapper, never()).update(any());
    }

    static Stream<Arguments> invalidCancellationPayStates() {
        return Stream.of(Cancellation.values()).flatMap(entry -> Stream.of(null, -1, Orders.REFUND, 3)
                .map(status -> Arguments.of(entry, status)));
    }

    @ParameterizedTest
    @MethodSource("invalidRefundAmounts")
    void invalidRefundAmountNeverReachesProvider(Cancellation entry, BigDecimal amount) {
        Orders original = order(Orders.TO_BE_CONFIRMED, Orders.PAID);
        original.setAmount(amount);
        when(orderMapper.getById(ORDER_ID)).thenReturn(original);
        assertThrows(OrderBusinessException.class, () -> cancel(entry));
        verifyNoInteractions(weChatPayUtil);
        verify(orderMapper, never()).update(any());
    }

    static Stream<Arguments> invalidRefundAmounts() {
        return Stream.of(Cancellation.values()).flatMap(entry -> invalidAmounts().map(amount -> Arguments.of(entry, amount)));
    }

    @ParameterizedTest
    @MethodSource("invalidRefundResponses")
    void refundErrorOrUnconfirmedResponseNeverChangesOrder(Cancellation entry, String response) throws Exception {
        when(orderMapper.getById(ORDER_ID)).thenReturn(order(Orders.TO_BE_CONFIRMED, Orders.PAID));
        when(weChatPayUtil.refund(ORDER_NUMBER, ORDER_NUMBER, AMOUNT, AMOUNT)).thenReturn(response);
        OrderBusinessException error = assertThrows(OrderBusinessException.class, () -> cancel(entry));
        assertEquals("退款申请未确认受理，请稍后查询", error.getMessage());
        verify(orderMapper, never()).update(any());
        verify(weChatPayUtil).refund(ORDER_NUMBER, ORDER_NUMBER, AMOUNT, AMOUNT);
    }

    static Stream<Arguments> invalidRefundResponses() {
        return Stream.of(Cancellation.values()).flatMap(entry -> Stream.of(
                null, "", "null", "not-json", "[]", "{}", "{\"code\":\"NOT_ENOUGH\",\"message\":\"余额不足\"}",
                refundResponse("CLOSED"), refundResponse("ABNORMAL"), refundResponse("UNKNOWN"),
                refundResponse("PROCESSING").replace("refund-101", ""),
                refundResponse("PROCESSING").replace("local-order-101", "other-order"),
                "{\"status\":\"PROCESSING\"}",
                refundResponse("SUCCESS").replace("\"status\":", "\"code\":\"ERROR\",\"status\":"))
                .map(response -> Arguments.of(entry, response)));
    }

    @ParameterizedTest
    @EnumSource(Cancellation.class)
    void providerExceptionLeavesOrderUnchanged(Cancellation entry) throws Exception {
        when(orderMapper.getById(ORDER_ID)).thenReturn(order(Orders.TO_BE_CONFIRMED, Orders.PAID));
        Exception failure = new Exception("mock provider unavailable");
        when(weChatPayUtil.refund(ORDER_NUMBER, ORDER_NUMBER, AMOUNT, AMOUNT)).thenThrow(failure);
        assertSame(failure, assertThrows(Exception.class, () -> cancel(entry)));
        verify(orderMapper, never()).update(any());
    }

    @Test
    void refundFailureUsesExistingBusinessErrorEnvelope() throws Exception {
        when(orderMapper.getById(ORDER_ID)).thenReturn(order(Orders.TO_BE_CONFIRMED, Orders.PAID));
        when(weChatPayUtil.refund(ORDER_NUMBER, ORDER_NUMBER, AMOUNT, AMOUNT)).thenReturn("{\"code\":\"ERROR\"}");
        OrderBusinessException error = assertThrows(OrderBusinessException.class, () -> cancel(Cancellation.USER));
        assertEquals(Integer.valueOf(0), new GlobalExceptionHandler().exceptionHandler(error).getCode());
        verify(orderMapper, never()).update(any());
    }

    private Orders updatedOrder() {
        ArgumentCaptor<Orders> update = ArgumentCaptor.forClass(Orders.class);
        verify(orderMapper).update(update.capture());
        assertEquals(ORDER_ID, update.getValue().getId());
        assertEquals(Orders.CANCELLED, update.getValue().getStatus());
        assertNotNull(update.getValue().getCancelTime());
        return update.getValue();
    }

    private void assertReason(Cancellation entry, Orders update) {
        if (entry == Cancellation.REJECTION) {
            assertEquals("mock rejection", update.getRejectionReason());
        } else {
            assertEquals(entry == Cancellation.USER ? "用户取消" : "mock cancellation", update.getCancelReason());
        }
    }

    private void cancel(Cancellation entry) throws Exception {
        switch (entry) {
            case USER:
                service.UserCancelById(ORDER_ID);
                break;
            case REJECTION:
                OrdersRejectionDTO rejection = new OrdersRejectionDTO();
                rejection.setId(ORDER_ID);
                rejection.setRejectionReason("mock rejection");
                service.rejection(rejection);
                break;
            case ADMIN:
                OrdersCancelDTO cancellation = new OrdersCancelDTO();
                cancellation.setId(ORDER_ID);
                cancellation.setCancelReason("mock cancellation");
                service.cancel(cancellation);
                break;
            default:
                throw new AssertionError(entry);
        }
    }

    private static Orders order(Integer status, Integer payStatus) {
        return Orders.builder().id(ORDER_ID).number(ORDER_NUMBER).userId(USER_ID)
                .amount(AMOUNT).status(status).payStatus(payStatus).build();
    }

    private static ShoppingCart cart(String amount, Integer number) {
        return ShoppingCart.builder().amount(amount == null ? null : new BigDecimal(amount))
                .number(number).userId(USER_ID).build();
    }

    private static OrdersSubmitDTO submitDto() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setAddressBookId(5L);
        dto.setTablewareNumber(1);
        return dto;
    }

    private static OrdersPaymentDTO paymentDto() {
        OrdersPaymentDTO dto = new OrdersPaymentDTO();
        dto.setOrderNumber(ORDER_NUMBER);
        return dto;
    }

    private static BigDecimal sumDetails(List<OrderDetail> details) {
        return details.stream().map(detail -> detail.getAmount().multiply(BigDecimal.valueOf(detail.getNumber())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String refundResponse(String status) {
        JSONObject response = new JSONObject();
        response.put("refund_id", "refund-101");
        response.put("out_trade_no", ORDER_NUMBER);
        response.put("out_refund_no", ORDER_NUMBER);
        response.put("status", status);
        return response.toJSONString();
    }
}
