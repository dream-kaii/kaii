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
import com.hmdp.utils.Constants.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.Constants.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.Constants.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result userCode(String phone, HttpSession session) {
        //最先采用session来保存用户登录信息以及验证码，现在优化直接采用redis来保存
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，直接返回错误信息
            return Result.fail(SystemConstants.PHONE_ERROR);
        }
        //3.符合，则生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码保存在redis中，供后续校验,这里需要将存入验证码进行2分钟有效操作
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码给手机号
        //这里用日志发送验证码来替代此过程
        log.info("验证码发送成功！验证码为：{}",code);

        return Result.ok();
    }

    @Override
    public Result userLogin(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        //用户登录操作
        //1.校验用户手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，直接返回错误信息
            return Result.fail(SystemConstants.PHONE_ERROR);
        }
        //2.验证码匹配操作
        String TCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(!TCode.equals(code)){
            return Result.fail(SystemConstants.PHONE_CODE_ERROR);
        }
        //3.查看是否为新用户
        //根据手机号在数据库中查询 select * from user where phone=?
        User user = query().eq("phone", phone).one();
        if(user==null){
            //为新用户需要创建用户并存入数据库中
            user=createUserWithPhone(phone);
        }
        //4.生成token值
        String token = UUID.randomUUID().toString(true);
        //5.将token为key用map数据将用户Dto保存在redis中
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                //设置忽略值为null的字段
                .setIgnoreNullValue(true)
                //设置value值转为String类型的
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String key=RedisConstants.LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(key,userMap);
        log.info("用户map为：{}",userMap);
        //6.设置token有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_CODE_TTL,TimeUnit.MINUTES);
        //7.返回token值给前端页面用来校验用户是否登录
        return Result.ok(token);
    }

    @Override
    public Result queryUserId(Long id) {
        //根据用户ID查询dto数据返回
        User user = getById(id);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String dateKey = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //获取当天在本月的位置 但日期是从一号开始的，所以减一
        int dayOfMonth = now.getDayOfMonth();
        String key=USER_SIGN_KEY+userId.toString()+dateKey;

        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获得连续签到次数，是离本日最近的一次
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String dateKey = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //获取当天在本月的位置 但日期是从一号开始的，所以减一
        int dayOfMonth = now.getDayOfMonth();
        String key=USER_SIGN_KEY+userId.toString()+dateKey;
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(results==null||results.isEmpty()){
            return Result.fail("无签到次数!");
        }
        Long num = results.get(0);
        if(num==null||num==0){
            return Result.fail("无签到次数!");
        }
        int count=0;
        while (true){
            //让获取到的签到bit的最后一位与1做与运算，如果为0则表示未签到直接返回
            if((num&1)==0){
                break;
            }
            else {
                count++;
            }
            //最后把数字右移一位，让下一个数字进行比较
            num >>>= 1;
        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        ///用户昵称
        String name=SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10);
        user.setNickName(name);
        //设置用户默认密码
        user.setPassword(RedisConstants.USER_PASSWORD);
        //保存用户到数据库中
        save(user);
        return user;
    }
}
