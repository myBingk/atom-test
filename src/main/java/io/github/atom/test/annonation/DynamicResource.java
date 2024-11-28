package io.github.atom.test.annonation;

import java.lang.annotation.*;

/**
 * 动态按需加载资源
 *
 * @author Zhang Kangkang
 * @version 1.0
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface DynamicResource {

    boolean dubboReference() default false;

}