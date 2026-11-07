package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        List<String> type_list_str = stringRedisTemplate.opsForList()
                .range(RedisConstants.SHOP_TYPE_LIST_KEY, 0, -1);

        if (type_list_str != null && !type_list_str.isEmpty()) {
            //命中，刷新缓存ttl，然后直接直接返回
            stringRedisTemplate
                    .expire(RedisConstants.SHOP_TYPE_LIST_KEY,
                            RedisConstants.CACHE_SHOP_TTL, TimeUnit.DAYS);
            log.debug("debug：shopType命中！");
            List<ShopType> typeList = type_list_str
                    .stream()
                    .map(jsonStr -> JSONUtil.toBean(jsonStr, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(typeList);
        }
        //未命中 查询数据库
        List<ShopType> type_list = list();
        if (type_list == null) {
            return Result.fail("未查询到商铺列表");
        }
        //查数据库查到了，缓存到redis
        //首先先把ShopType类型的list的数据转换成json字符串类型的的list,然后缓存到redis，同时设置ttl
        type_list_str = type_list.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().
                rightPushAll(RedisConstants.SHOP_TYPE_LIST_KEY, type_list_str);
        stringRedisTemplate.expire(RedisConstants.SHOP_TYPE_LIST_KEY, RedisConstants.CACHE_SHOP_TTL, TimeUnit.DAYS);
        log.debug("debug：shopType未命中");
        //返回数据库查询的ShopType类型的list，不要返回字符串类型的的list，不然前端接收到的数据格式不对，无法渲染显示
        return Result.ok(type_list);
    }

}
