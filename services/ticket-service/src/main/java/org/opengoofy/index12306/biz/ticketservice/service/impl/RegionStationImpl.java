/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.common.enums.RegionStationQueryTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.RegionDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.StationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.RegionMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.StationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RegionStationQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RegionStationQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.StationQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.RegionStationService;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.cache.core.CacheLoader;
import org.opengoofy.index12306.framework.starter.cache.toolkit.CacheUtil;
import org.opengoofy.index12306.framework.starter.common.enums.FlagEnum;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.STATION_ALL;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_STATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_QUERY_REGION_STATION_LIST;

/**
 * 地区以及车站接口实现层
 *
 * @公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Service
@RequiredArgsConstructor
public class RegionStationImpl implements RegionStationService {

    private final RegionMapper regionMapper;
    private final StationMapper stationMapper;
    private final DistributedCache distributedCache;
    private final RedissonClient redissonClient;

    @Override
    public List<RegionStationQueryRespDTO> listRegionStation(RegionStationQueryReqDTO requestParam) {
        String key;
        // 当只有 name 参数时，判断是否为车站名称或者拼音
        if (StrUtil.isNotBlank(requestParam.getName())) {
            key  = REGION_STATION  + requestParam.getName();
            return safeGetRegionStation(
                    key ,
                    () -> {
                        LambdaQueryWrapper<StationDO> queryWrapper = Wrappers.lambdaQuery(StationDO.class)
                                .likeRight(StationDO::getName, requestParam.getName())
                                .or()
                                .likeRight(StationDO::getSpell, requestParam.getName());
                        List<StationDO> stationDOList = stationMapper.selectList(queryWrapper);
                        return JSON.toJSONString(BeanUtil.convert(stationDOList, RegionStationQueryRespDTO.class));
                    },
                    requestParam.getName()
            );
        }
        // 0-5 对应为 热门、A-E、F-J、K-O、P-T、U-Z
        key  = REGION_STATION  + requestParam.getQueryType();
        LambdaQueryWrapper<RegionDO> queryWrapper = switch (requestParam.getQueryType()) {
            case 0 -> Wrappers.lambdaQuery(RegionDO.class)
                    .eq(RegionDO::getPopularFlag, FlagEnum.TRUE.code());
            case 1 -> Wrappers.lambdaQuery(RegionDO.class)
                    .in(RegionDO::getInitial, RegionStationQueryTypeEnum.A_E.getSpells());
            case 2 -> Wrappers.lambdaQuery(RegionDO.class)
                    .in(RegionDO::getInitial, RegionStationQueryTypeEnum.F_J.getSpells());
            case 3 -> Wrappers.lambdaQuery(RegionDO.class)
                    .in(RegionDO::getInitial, RegionStationQueryTypeEnum.K_O.getSpells());
            case 4 -> Wrappers.lambdaQuery(RegionDO.class)
                    .in(RegionDO::getInitial, RegionStationQueryTypeEnum.P_T.getSpells());
            case 5 -> Wrappers.lambdaQuery(RegionDO.class)
                    .in(RegionDO::getInitial, RegionStationQueryTypeEnum.U_Z.getSpells());
            default -> throw new ClientException("查询失败，请检查查询参数是否正确");
        };
        return safeGetRegionStation(
                key,
                () -> {
                    List<RegionDO> regionDOList = regionMapper.selectList(queryWrapper);
                    return JSON.toJSONString(BeanUtil.convert(regionDOList, RegionStationQueryRespDTO.class));
                },
                String.valueOf(requestParam.getQueryType())
        );
    }

    @Override
    public List<StationQueryRespDTO> listAllStation() {
        return distributedCache.safeGet(
                STATION_ALL,
                List.class,
                () -> BeanUtil.convert(stationMapper.selectList(Wrappers.emptyWrapper()), StationQueryRespDTO.class),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS
        );
    }

    private  List<RegionStationQueryRespDTO> safeGetRegionStation(final String key, CacheLoader<String> loader, String param) {
        List<RegionStationQueryRespDTO> result;
        // 从缓存中获取
        if (CollUtil.isNotEmpty(result = JSON.parseArray(distributedCache.get(key, String.class), RegionStationQueryRespDTO.class))) {
            return result;
        }
        String lockKey = String.format(LOCK_QUERY_REGION_STATION_LIST, param);
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try {
            // 缓存未命中，从数据库中获取
            if (CollUtil.isEmpty(result = JSON.parseArray(distributedCache.get(key, String.class), RegionStationQueryRespDTO.class))) {
                // 数据库中也没有，直接返回空集合
                if (CollUtil.isEmpty(result = loadAndSet(key, loader))) {
                    return Collections.emptyList();
                }
            }
        } finally {
            lock.unlock();
        }
        return result;
    }

    private List<RegionStationQueryRespDTO> loadAndSet(final String key, CacheLoader<String> loader) {
        // 数据库中获取
        String result = loader.load();
        // 判断是否为空
        if (CacheUtil.isNullOrBlank(result)) {
            return Collections.emptyList();
        }
        // 解析为对象
        List<RegionStationQueryRespDTO> respDTOList = JSON.parseArray(result, RegionStationQueryRespDTO.class);
        // 写入缓存
        distributedCache.put(
                key,
                result,
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS
        );
        return respDTOList;
    }
}
