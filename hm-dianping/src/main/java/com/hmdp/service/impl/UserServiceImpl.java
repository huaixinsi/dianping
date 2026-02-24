package com.hmdp.service.impl;

import cn.hutool.Hutool;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.db.Session;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    public Result sendCode(String phone, HttpSession session)
    {
        // 校验手机号是否为空
        if (phone == null || phone.trim().isEmpty()) {
            // 无效的手机号，直接返回，不进行后续处理
            return Result.fail("手机号不能为空");
        }
        //通过正则表达式校验手机号
        String regex = "^1[3-9]\\d{9}$";
        if (!phone.matches(regex)) {
            // 无效的手机号格式，直接返回，不进行后续处理
            return Result.fail("手机号格式不正确");
        }
        //生成验证码，并保存到session
        String code = RandomUtil.randomNumbers(6);
        session.setAttribute("code", code);
        //发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }
    public Result login(LoginFormDTO loginForm, HttpSession session)
    {
        //判断手机号是否为空
        // 校验手机号是否为空
        if (loginForm.getPhone() == null || loginForm.getPhone().trim().isEmpty()) {
            // 无效的手机号，直接返回，不进行后续处理
            return Result.fail("手机号不能为空");
        }
        //校验验证码是否正确
        String code = (String) session.getAttribute("code");
        if (code == null || !code.equals(loginForm.getCode())) {
            // 验证码不正确，直接返回，不进行后续处理
            return Result.fail("验证码不正确");
        }
        //判断手机号是否存在在数据库里
        User user =query().eq("phone",loginForm.getPhone()).one();
        if (user != null) {
            //如果存在，直接登录,将用户信息保存到session中
            session.setAttribute("user", user);
            return Result.ok();
        }

        //如果不存在，创建新用户并保存到数据库
        user = new User();
        user.setPhone(loginForm.getPhone());
        user.setNickName(RandomUtil.randomString(6));
        //保存用户到数据库,利用mybatisplus的save方法
        save(user);
        //将用户信息保存到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        return Result.ok();
    }
}
