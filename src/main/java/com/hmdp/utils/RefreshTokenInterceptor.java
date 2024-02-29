package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    // 不是真正的拦截，只是为了刷新带有token的用户的登录状态，没token的直接放行
    // 拦截功能在下一层拦截器实现

   // 无法使用注解注入，因为当前类不能被Spring自动创建bean对象，是我们自己创建的，所以要使用构造函数
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {


        // 前端已经处理好token，并在每次请求中携带token请求头,前端设置他叫做authorization
        // 1.从请求头中获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String cacheToken = LOGIN_USER_KEY + token;
        // 2.从redis中查找对应的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(cacheToken);
        // redis 判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }

        // 用户存在，保存用户到ThreadLocal
        // redis先把map转换再存
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        // redis需要刷新token的有效期，访问就刷新，一段时间不访问才会到期销毁
        stringRedisTemplate.expire(cacheToken,LOGIN_USER_TTL, TimeUnit.MINUTES);

        //放行
        return true;
    }

    // 编译后
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
       // 移除用户，避免内存泄漏
        UserHolder.removeUser();
    }
}
