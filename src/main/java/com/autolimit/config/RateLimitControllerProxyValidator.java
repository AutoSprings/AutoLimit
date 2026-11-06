package com.autolimit.config;

import com.autolimit.aop.RateLimitAspect;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;

import java.util.Arrays;

/** Detects controller stereotypes hidden by quota-advised JDK proxies. */
final class RateLimitControllerProxyValidator implements BeanPostProcessor {
    // Deliberately unordered: runs after Spring's Ordered auto-proxy creator.
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (AopUtils.isJdkDynamicProxy(bean) && bean instanceof Advised advised
                && Arrays.stream(advised.getAdvisors()).anyMatch(RateLimitAspect.class::isInstance)
                && AnnotatedElementUtils.hasAnnotation(AopProxyUtils.ultimateTargetClass(bean), Controller.class)
                && !AnnotatedElementUtils.hasAnnotation(bean.getClass(), Controller.class)) {
            throw new BeanCreationException(beanName,
                    "AutoLimit quota proxy hides the controller stereotype. Set spring.aop.proxy-target-class=true "
                    + "(or enable class proxies), or put @Controller/@RestController on the controller interface.");
        }
        return bean;
    }
}
