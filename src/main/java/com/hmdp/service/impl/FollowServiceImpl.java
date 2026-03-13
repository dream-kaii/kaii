package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserServiceImpl userService;
    @Resource
    private BlogServiceImpl blogService;
    @Override
    public Result Follow(Long id, Boolean isFollow) {
        Long userID = UserHolder.getUser().getId();
        String key="Follow:"+userID;
        //先判断是关注操作还是取关操作
        if (BooleanUtil.isTrue(isFollow)){
            //说明当前用户对id被关注人进行关注操作
            Follow follow = new Follow();
            follow.setFollowUserId(id);
            follow.setUserId(userID);
            boolean isSuccess = save(follow);
            if(isSuccess){
                //如果保存成功，将关注的人保存在redis中
                stringRedisTemplate.opsForSet().add(key,id.toString());
            }
        }
        else {
            //取关操作
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userID)
                    .eq("follow_user_id", id));
            if (isSuccess){
                //删除
                stringRedisTemplate.opsForSet().remove(key,id.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        //判断是否关注,就是查询当前用户和被关注人id是否在表中有记录
        Long userId = UserHolder.getUser().getId();
        String key="Follow:"+userId;
        Boolean isFollow = stringRedisTemplate.opsForSet().isMember(key, id.toString());
        /*Integer count = query()
                .eq("user_id", userId)
                .eq("follow_user_id", id)
                .count();*/
        return Result.ok(BooleanUtil.isTrue(isFollow));
    }

    @Override
    public Result AllFollow(Long id) {
        //所给值为当前查看用户ID，根据当前用户ID和自己登录用户ID去redis中查交集
        Long myId = UserHolder.getUser().getId();
        String key="Follow:"+myId;
        String key2="Follow:"+id;
        //共同关注人的ID
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(set==null||set.isEmpty()){
            return Result.fail("无共同好友！");
        }
        List<Long> list = set.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        List<User> users = userService.listByIds(list);
        List<UserDTO> dtos = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(dtos);
    }


}
