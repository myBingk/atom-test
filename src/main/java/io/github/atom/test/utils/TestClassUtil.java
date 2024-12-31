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
     * 测试类构造器
     */
    private TestClassUtil() {

        throw new UnsupportedOperationException("util cannot be instantiated");
    }

    /**
     * 是否实现接口
     *
     * @param clazz         类
     * @param interfaceName 接口名
     * @return 是否实现接口
     */
    public static boolean hasInterface(Class<?> clazz, String interfaceName) {

        Class<?> interfaceClass = tryGetClass(interfaceName);
        if (interfaceClass == null) {
            return false;
        }
        return ClassUtils.isAssignable(interfaceClass, clazz);
    }

    /**
     * 根据class名称加载class
     *
     * @param className class名称
     * @return class
     */
    public static Class<?> tryGetClass(String className) {

        try {
            return Class.forName(className);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 获取注解信息
     *
     * @param className 注解类
     * @return 注解信息
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
     * @return 实例对象
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
     * 数组是否为空
     *
     * @param array 数组
     * @param <T>   数组类型
     * @return 是否为空
     */
    public static <T> boolean isArrayEmpty(T[] array) {

        return array == null || array.length == 0;
    }

    /**
     * 数组是否不为空
     *
     * @param array 数组
     * @param <T>   数组类型
     * @return 是否不为空
     */
    public static <T> boolean isArrayNotEmpty(T[] array) {

        return !isArrayEmpty(array);
    }

    /**
     * 是否基本类型或包装类型
     *
     * @param clazz 类
     * @return 是否基本类型或包装类型
     */
    public static boolean isPrimitiveOrWrapper(Class<?> clazz) {

        return clazz.isPrimitive() || WRAPPER_TYPES.contains(clazz);
    }

    /**
     * 获取泛型实际类型
     *
     * @param genericSuperclass 泛型
     * @param actualType        实际类型
     * @return 泛型实际类型
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
