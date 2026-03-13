package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.Constants.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Wrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.Constants.RedisConstants.BLOG_LIKE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private FollowServiceImpl followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        blog.setUserId(userId);
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(isSuccess){
            //查询有哪些用户关注我即我的粉丝们
            List<Follow> funs = followService.query().eq("follow_user_id", userId).list();
            for (Follow fun : funs) {
                //遍历我的粉丝获取ID 并一一将我的博客ID推送给他们
                Long GiveId = fun.getUserId();
                String key="Follow:Blog:email:"+GiveId;
                stringRedisTemplate
                        .opsForZSet()
                        .add(key,blog.getId().toString(),System.currentTimeMillis());
            }
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            createBlogLiked(blog);
        });
        return Result.ok(records);
    }

    //根据博客ID查询博客信息
    @Override
    public Result queryBlog(Long id) {
        //查询
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("笔记不存在！");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        createBlogLiked(blog);
        return Result.ok(blog);
    }
    public void createBlogLiked(Blog blog){
        Long id = blog.getId();
        String Blog_Like_Id=BLOG_LIKE_KEY+id;
        UserDTO user = UserHolder.getUser();
        if (user==null){
            return;
        }
        Long userId = user.getId();
        Double isUSer = stringRedisTemplate.opsForZSet().score(Blog_Like_Id, userId.toString());
        if(isUSer!=null){
            //为true说明点赞过了
            blog.setIsLike(true);
        }
        else {
            blog.setIsLike(false);
        }
    }

    @Override
    public Result likeBlog(Long id) {
        // 修改点赞数量
        //一人一赞
        ///一个用户只能对一篇笔记点赞一次
        //先在redis中判断是否存在该用户
        String key=BLOG_LIKE_KEY+id;
        Long userId = UserHolder.getUser().getId();
        Double isUSer = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        if(isUSer!=null){
            //说明存在有User点赞 取消点赞
            boolean isTrue = update().setSql("liked = liked - 1 ").eq("id", id).update();
            if(isTrue){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
            else {
                return Result.fail("点赞异常！！");
            }
        }else {
            //添加到set集合中
            boolean isTrue = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isTrue){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
            else {
                return Result.fail("点赞异常！！");
            }
        }
        return Result.ok();
    }

    @Override
    public Result likesBlog(Long id) {
        //查看前五名的点赞信息
        String key=BLOG_LIKE_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            //如果不存在直接返回
            return Result.ok();
        }
        //通过stream流获得用户ID信息
        List<Long> UserIds = top5
                .stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        String idStr= StrUtil.join(",",UserIds);
        List<UserDTO> userDTOS = userService
                .query()
                .in("id",UserIds)
                .last("ORDER BY FIELD(id,"+idStr+")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogByUserId(Long id, Integer current) {
        //根据用户ID查询笔记并分页
        Page<Blog> page = query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();

        return Result.ok(records);
    }

    @Override
    public Result queryFollowPageBlog(Long lastId, Integer offset) {
        //实现关注推送博客功能
        //从当前用户中的收件箱中获取页面数量的博客ID
        Long id = UserHolder.getUser().getId();
        String key="Follow:Blog:email:"+id;
        log.info("当前查看人的ID为{},时间戳为{},偏移量为：{}",key,lastId,offset);
        Set<ZSetOperations.TypedTuple<String>> top10 = stringRedisTemplate
                .opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        log.info("查到的邮件箱内容为：{}",top10);
        if (top10==null||top10.isEmpty()){
            return Result.fail("没有博主更新!");
        }
        ArrayList<String> BlogsID=new ArrayList<>();
        ArrayList<Double> scores=new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> entry : top10) {
            BlogsID.add(entry.getValue());
            scores.add(entry.getScore());
        }
        //获得最后一次时间戳 即最小
        Double minTime = Collections.min(scores);
        int NewOffset = 0;
        for (int i = 0; i < scores.size(); i++) {
            Double s = scores.get(i);
            if(minTime.equals(s)){
                NewOffset++;
            }
        }
        List<Long> ids = BlogsID.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id",ids)
                .last("ORDER BY FIELD(id,"+idStr+")")
                .list();
        for (Blog blog : blogs) {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            createBlogLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(NewOffset);
        scrollResult.setMinTime(minTime.longValue());
        return Result.ok(scrollResult);
    }
}
