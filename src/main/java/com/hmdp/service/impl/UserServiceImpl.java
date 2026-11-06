package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RegexUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效");
        }

        // 频控：验证码未过期前不允许重复发送，防止短信轰炸
        Boolean firstSend = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOGIN_CODE_KEY + phone, "1", LOGIN_CODE_TTL, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(firstSend)) {
            return Result.fail("验证码发送过于频繁，请稍后再试");
        }

        String code = RandomUtil.randomString(6);

        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // TODO 发送短信验证码
        log.debug("验证码 = " + code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效");
        }

        String code = loginForm.getCode();
        String password = loginForm.getPassword();

        User user;
        if (StrUtil.isNotBlank(password)) {
            // 密码登录
            user = query().eq("phone", phone).one();
            if (user == null) {
                return Result.fail("用户不存在，请先使用验证码登录注册");
            }
            if (!PasswordEncoder.matches(user.getPassword(), password)) {
                return Result.fail("密码错误");
            }
        } else {
            // 验证码登录
            if (StrUtil.isBlank(code)) {
                return Result.fail("验证码不能为空");
            }
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            if (cacheCode == null || !cacheCode.equals(code)) {
                return Result.fail("验证码错误");
            }
            // 校验通过后立即删除验证码，防止重复使用
            stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

            user = query().eq("phone", phone).one();
            if (user == null) {
                // 如果用户不存在，则创建新用户
                user = createUserWithPhone(phone);
            }
        }

        String token = UUID.randomUUID().toString(true);

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);

        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 返回 token，前端会将其存入 sessionStorage 并随请求头 authorization 回传
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10) + phone);
        save(user);
        return user;
    }
}
