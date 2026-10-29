package com.sky;

import com.sky.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopStatusTest {
    private static final String KEY = "SHOP_STATUS";
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> values;
    private final com.sky.controller.admin.ShopController admin = new com.sky.controller.admin.ShopController();
    private final com.sky.controller.user.ShopController user = new com.sky.controller.user.ShopController();

    @BeforeEach
    void injectMockRedis() {
        ReflectionTestUtils.setField(admin, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(user, "redisTemplate", redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void adminWritesAreVisibleOnBothSides(int status) {
        Map<String, Object> redis = new HashMap<>();
        when(redisTemplate.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), any());
        when(values.get(KEY)).thenAnswer(invocation -> redis.get(KEY));

        assertEquals(KEY, com.sky.controller.admin.ShopController.KEY);
        assertEquals(KEY, com.sky.controller.user.ShopController.KEY);
        assertEquals(Integer.valueOf(1), admin.setStatus(status).getCode());
        assertStatus(status, admin.getStatus());
        assertStatus(status, user.getStatus());
        verify(values).set(KEY, status);
        verify(values, times(2)).get(KEY);
    }

    @Test
    void absentStatusDefaultsToClosedOnBothSides() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        assertStatus(0, admin.getStatus());
        assertStatus(0, user.getStatus());
        verify(values, times(2)).get(KEY);
        verify(values, never()).set(anyString(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 2, 99})
    void invalidWritesFailWithoutTouchingRedis(Integer status) {
        Result<?> result = admin.setStatus(status);
        assertEquals(Integer.valueOf(0), result.getCode());
        assertNotNull(result.getMsg());
        verifyNoInteractions(redisTemplate, values);
    }

    @ParameterizedTest
    @MethodSource("invalidStoredValues")
    void invalidStoredValuesFailClosedOnBothSides(Object value) {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenReturn(value);
        assertStatus(0, admin.getStatus());
        assertStatus(0, user.getStatus());
    }

    static Stream<Object> invalidStoredValues() {
        return Stream.of(-1, 2, "1", "invalid", 1L);
    }

    @Test
    void readConnectionFailureIsNotDisguisedAsClosed() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("mock unavailable");
        when(values.get(KEY)).thenThrow(failure);
        assertSame(failure, assertThrows(RedisConnectionFailureException.class, admin::getStatus));
        assertSame(failure, assertThrows(RedisConnectionFailureException.class, user::getStatus));
    }

    @Test
    void writeConnectionFailureIsNotDisguisedAsSuccess() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("mock unavailable");
        doThrow(failure).when(values).set(KEY, 1);
        assertSame(failure, assertThrows(RedisConnectionFailureException.class, () -> admin.setStatus(1)));
    }

    private void assertStatus(int expected, Result<Integer> result) {
        assertEquals(Integer.valueOf(1), result.getCode());
        assertEquals(Integer.valueOf(expected), result.getData());
    }
}
