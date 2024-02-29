package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

public class LoginInterceptor implements HandlerInterceptor {
    // 没有RefreshTokenInterceptor时

//   // 无法使用注解注入，因为当前类不能被Spring自动创建bean对象，是我们自己创建的，所以要使用构造函数
//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

//    @Override
//    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
////        // 获取session
////        HttpSession session = request.getSession();
////        // 获取session中的用户
////        Object user = session.getAttribute("user");
//
//        // 前端已经处理好token，并在每次请求中携带token请求头,前端设置他叫做authorization
//        // 1.从请求头中获取token
//        String token = request.getHeader("authorization");
//        if (StrUtil.isBlank(token)) {
//            // 不存在，拦截.401状态码默认为未授权
//            response.setStatus(401);
//            return false;
//        }
//        String cacheToken = LOGIN_USER_KEY + token;
//        // 2.从redis中查找对应的用户
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(cacheToken);
//        // redis 判断用户是否存在
//        if (userMap.isEmpty()) {
//            // 不存在，拦截.401状态码默认为未授权
//            response.setStatus(401);
//            return false;
//        }
//
//
////        // session判断用户是否存在
////        if (user == null){
////            // 用户不存在，拦截.401状态码默认为未授权
////            response.setStatus(401);
////            return false;
////        }
//
//        // 用户存在，保存用户到ThreadLocal
////        UserHolder.saveUser((UserDTO) user);  // session直接存
//        // redis先把map转换再存
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        UserHolder.saveUser(userDTO);
//        // redis需要刷新token的有效期，访问就刷新，一段时间不访问才会到期销毁
//        stringRedisTemplate.expire(cacheToken,LOGIN_USER_TTL, TimeUnit.MINUTES);
//
//        //放行
//        return true;
//    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null){
            // 没有用户，拦截
            response.setStatus(401);
            return false;
        }
        // 有用户，则放行
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
       // 移除用户，避免内存泄漏
        UserHolder.removeUser();
    }
}
