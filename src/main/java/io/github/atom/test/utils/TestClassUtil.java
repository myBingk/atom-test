package io.github.atom.test.utils;

import org.springframework.util.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 测试类工具
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
public final class TestClassUtil {

    /**
     * 包装类型
     */
    private static final Set<Class<?>> WRAPPER_TYPES = new HashSet<Class<?>>() {{
        add(Object.class);
        add(Boolean.class);
        add(Byte.class);
        add(Character.class);
        add(Double.class);
        add(Float.class);
        add(Integer.class);
        add(Long.class);
        add(Short.class);
        add(Void.class);
        add(String.class);
    }};

    /**
     * 构造方法
     */
    private TestClassUtil() {

        throw new UnsupportedOperationException("util cannot be instantiated");
    }

    /**
     * 判断类是否实现接口
     *
     * @param clazz         类型
     * @param interfaceName 接口名
     * @return 类是否实现接口
     */
    public static boolean hasInterface(Class<?> clazz, String interfaceName) {

        Class<?> interfaceClass = tryGetClass(interfaceName);
        if (interfaceClass == null) {
            return false;
        }
        return ClassUtils.isAssignable(interfaceClass, clazz);
    }

    /**
     * 根据名称获取类
     *
     * @param className 名称
     * @return 类
     */
    public static Class<?> tryGetClass(String className) {

        try {
            return Class.forName(className);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 根据名称获取注解
     *
     * @param className 名称
     * @return 注解
     */
    public static Class<? extends Annotation> tryGetAnnotation(String className) {

        try {
            Class<?> clazz = tryGetClass(className);
            if (Objects.isNull(clazz)) {
                return null;
            }
            return (Class<? extends Annotation>)clazz;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 实例化对象
     *
     * @param clazz 类
     * @return 对象
     */
    public static Object tryInstance(Class<?> clazz) {

        if (Objects.isNull(clazz)) {
            return null;
        }
        try {
            return clazz.newInstance();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 是否空数组
     *
     * @param array 数组
     * @param <T>   数组类型
     * @return 是否空数组
     */
    public static <T> boolean isArrayEmpty(T[] array) {

        return array == null || array.length == 0;
    }

    /**
     * 是否非空数组
     *
     * @param array 数组
     * @param <T>   数组类型
     * @return 是否非空数组
     */
    public static <T> boolean isArrayNotEmpty(T[] array) {

        return !isArrayEmpty(array);
    }

    /**
     * 是否基本类型
     *
     * @param clazz 类
     * @return 是否基本类型
     */
    public static boolean isPrimitiveOrWrapper(Class<?> clazz) {

        return clazz.isPrimitive() || WRAPPER_TYPES.contains(clazz);
    }

    /**
     * 获取泛型的实际类
     *
     * @param genericSuperclass 泛型
     * @param actualType        实际类
     * @return 泛型的实际类
     */
    public static Class<?> getActualClass(Type genericSuperclass, Class<?> actualType) {

        if (genericSuperclass instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType)genericSuperclass;
            if (Objects.nonNull(parameterizedType.getActualTypeArguments())
                && parameterizedType.getActualTypeArguments().length > 0) {
                actualType = TestClassUtil.tryGetClass(parameterizedType.getActualTypeArguments()[0].getTypeName());
            }
        }
        return actualType;
    }

}
