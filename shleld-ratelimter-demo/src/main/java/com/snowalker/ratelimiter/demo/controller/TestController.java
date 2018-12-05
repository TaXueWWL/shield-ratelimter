package com.snowalker.ratelimiter.demo.controller;

import com.snowalker.shield.ratelimiter.core.annotation.RateLimiter;
import org.apache.catalina.servlet4preview.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * @author snowalker
 * @version 1.0
 * @date 2018/10/27 2:13
 * @className
 * @desc
 */
@Controller
public class TestController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestController.class);

    private static final String MESSAGE = "{\"code\":\"400\",\"msg\":\"FAIL\",\"desc\":\"触发限流\"}";

    @ResponseBody
    @RequestMapping("ratelimiter")
    @RateLimiter(key = "ratedemo:1.0.0", limit = 5, expire = 10, message = MESSAGE)
    public String sendPayment(HttpServletRequest request) throws Exception {

        return "正常请求";
    }

    @ResponseBody
    @RequestMapping("ratelimiter1")
    @RateLimiter(key = "ratedemo:1.0.1", limit = 5, expire = 10, message = MESSAGE)
    public String sendPayment1(HttpServletRequest request) throws Exception {

        return "正常请求";
    }
}
