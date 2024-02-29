package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            // 2.如果不符合，返回错误信息
            return Result.fail("无效的手机号");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

//        // 4.保存验证码到session
//        session.setAttribute("code",code);

        // 4.保存验证码到redis，以手机号作为key存入,ttl = 2L, 后面给单位
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：" + code);
        // 返回ok
        return Result.ok("发送短信验证码成功，验证码：" + code);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.取出手机号和验证码
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 2.校验手机号和验证码的格式
        if (RegexUtils.isPhoneInvalid(phone) || RegexUtils.isCodeInvalid(code)){
            // 3.手机号错误或验证码格式错误
            return Result.fail("手机号或验证码格式错误");
        }
//        // 4.从session获取验证码，校验验证码是否正确
//        Object cacheCode = session.getAttribute("code");

        // 4.从redis获取验证码，并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null){
            return Result.fail("请先发送验证码");
        }
        // 现在可以保证cacheCode不为null，但是没有保证code不为null，所以code不能用来.equals
        if (!cacheCode.toString().equals(code)){
            // 5.验证码错误
            return Result.fail("验证码错误");
        }
        // 6.验证码正确，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        // 7.判断用户是否存在
        if (user == null){
            // 用户不存在，转变为注册，将用户基本信息(手机号+随机昵称)存入数据库
            user = createUserWithPhone(phone);
        }

//        // 8.用户存在，保存用户到session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 8.如果用redis存用户信息，那么给前端需要返回存入redis中的key，区别于Tomcat自动维护的session
        // 8.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 8.2 将User对象转为Hash存储所需要的形式
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 因为userDTO中的id字段是Long类型，StringRedisTemplate处理的是Map<String, String>
        // 方法一：手动实现方法，将userDTO字段类型转为String存入userMap
        // 方法二：BeanUtil.beanToMap方法可以加参数自定义配置，完成类型转换，有点抽象奥！！
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 8.3存储到redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 设置有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        // 8.4 返回token, 前端访问进来时再给加上前缀去redis找，更安全
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
        // 插入到数据库
        save(user);
        return user;
    }
}
