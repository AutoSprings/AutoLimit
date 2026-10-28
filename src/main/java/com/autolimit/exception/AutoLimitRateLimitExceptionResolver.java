package com.autolimit.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;


@Order(Ordered.HIGHEST_PRECEDENCE)
public class AutoLimitRateLimitExceptionResolver implements HandlerExceptionResolver {

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {

        if (ex instanceof RateLimitExceededException) {
            try {
                response.setContentType("application/json");
                response.setStatus(429);
                response.getWriter().write("{\"error\": \"Too Many Requests\", \"message\": \"Rate limit exceeded. Please try again later.\"}");
                response.getWriter().flush();

                return new ModelAndView();
            } catch (IOException e) {
            }
        }

        return null;
    }
}