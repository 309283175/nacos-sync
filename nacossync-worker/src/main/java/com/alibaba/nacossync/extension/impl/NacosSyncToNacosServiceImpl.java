/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.nacossync.extension.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.common.utils.CollectionUtils;
import com.alibaba.nacossync.cache.SkyWalkerCacheServices;
import com.alibaba.nacossync.constant.ClusterTypeEnum;
import com.alibaba.nacossync.constant.MetricsStatisticsType;
import com.alibaba.nacossync.constant.SkyWalkerConstants;
import com.alibaba.nacossync.extension.SyncService;
import com.alibaba.nacossync.extension.annotation.NacosSyncService;
import com.alibaba.nacossync.extension.holder.NacosServerHolder;
import com.alibaba.nacossync.monitor.MetricsManager;
import com.alibaba.nacossync.pojo.model.TaskDO;
import com.alibaba.nacossync.util.Collections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author yangyshdan
 * @version $Id: ConfigServerSyncManagerService.java, v 0.1 2018-11-12 下午5:17 NacosSync Exp $$
 */

@Slf4j
@NacosSyncService(sourceCluster = ClusterTypeEnum.NACOS, destinationCluster = ClusterTypeEnum.NACOS)
public class NacosSyncToNacosServiceImpl implements SyncService {

    private Map<String, EventListener> listenerMap = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> sourceInstanceSnapshot = new ConcurrentHashMap<>();

    @Autowired
    private MetricsManager metricsManager;

    @Autowired
    private SkyWalkerCacheServices skyWalkerCacheServices;

    @Autowired
    private NacosServerHolder nacosServerHolder;

    @Override
    public boolean delete(TaskDO taskDO) {
        try {

            NamingService sourceNamingService =
                nacosServerHolder.get(taskDO.getSourceClusterId(), taskDO.getGroupName());
            NamingService destNamingService = nacosServerHolder.get(taskDO.getDestClusterId(), taskDO.getGroupName());
            //移除订阅
            sourceNamingService.unsubscribe(taskDO.getServiceName(), listenerMap.remove(taskDO.getTaskId()));
            sourceInstanceSnapshot.remove(taskDO.getTaskId());

            // 删除目标集群中同步的实例列表
            List<Instance> sourceInstances = sourceNamingService.getAllInstances(taskDO.getServiceName(),
                new ArrayList<>(), false);
            for (Instance instance : sourceInstances) {
                if (needSync(instance.getMetadata())) {
                    destNamingService.deregisterInstance(taskDO.getServiceName(), instance.getIp(), instance.getPort());
                }
            }
        } catch (Exception e) {
            log.error("delete task from nacos to nacos was failed, taskId:{}", taskDO.getTaskId(), e);
            metricsManager.recordError(MetricsStatisticsType.DELETE_ERROR);
            return false;
        }
        return true;
    }

    @Override
    public boolean sync(TaskDO taskDO) {
        String taskId = taskDO.getTaskId();
        try {
            NamingService sourceNamingService =
                nacosServerHolder.get(taskDO.getSourceClusterId(), taskDO.getGroupName());
            NamingService destNamingService = nacosServerHolder.get(taskDO.getDestClusterId(), taskDO.getGroupName());

            this.listenerMap.putIfAbsent(taskId, event -> {
                if (event instanceof NamingEvent) {
                    try {

                        List<Instance> sourceInstances = sourceNamingService.getAllInstances(taskDO.getServiceName(),
                            new ArrayList<>(), false);
                        log.info("任务Id:{},迁入实例数量:{}", taskId, sourceInstances.size());
                        // 先删除不存在的
                        this.removeInvalidInstance(taskDO, taskId, destNamingService, sourceInstances);

                        //再次添加新实例
                        for (Instance instance : sourceInstances) {
                            if (needSync(instance.getMetadata())) {
                                destNamingService.registerInstance(taskDO.getServiceName(),
                                    buildSyncInstance(instance, taskDO));
                                this.sourceInstanceSnapshot.compute(taskId, (key, value) -> {
                                    if (CollectionUtils.isEmpty(value)) {
                                        value = new TreeSet<>();
                                    }
                                    log.info("任务Id:{},已同步实例:{}", taskId, composeInstanceKey(instance));
                                    value.add(composeInstanceKey(instance));
                                    return value;
                                });
                            }
                        }

                    } catch (Exception e) {
                        log.error("event process fail, taskId:{}", taskId, e);
                        metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
                    }
                }
            });

            sourceNamingService.subscribe(taskDO.getServiceName(), listenerMap.get(taskId));
        } catch (Exception e) {
            log.error("sync task from nacos to nacos was failed, taskId:{}", taskId, e);
            metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
            return false;
        }
        return true;
    }

    private void removeInvalidInstance(TaskDO taskDO, String taskId, NamingService destNamingService,
        List<Instance> sourceInstances) throws NacosException {
        if (this.sourceInstanceSnapshot.containsKey(taskId)) {
            Set<String> oldInstanceKeys = this.sourceInstanceSnapshot.get(taskId);
            List<String> newInstanceKeys = sourceInstances.stream().map(this::composeInstanceKey)
                .collect(Collectors.toList());
            Collection<String> instanceKeys = Collections.subtract(oldInstanceKeys, newInstanceKeys);
            for (String instanceKey : instanceKeys) {
                log.info("任务Id:{},移除无效同步实例:{}", taskId, instanceKey);
                String[] split = instanceKey.split(":", -1);
                destNamingService.deregisterInstance(taskDO.getServiceName(), split[0],
                    Integer.parseInt(split[1]));

            }
        }
    }

    private String composeInstanceKey(Instance instance) {
        return instance.getIp() + ":" + instance.getPort();
    }


    public Instance buildSyncInstance(Instance instance, TaskDO taskDO) {
        Instance temp = new Instance();
        temp.setIp(instance.getIp());
        temp.setPort(instance.getPort());
        temp.setClusterName(instance.getClusterName());
        temp.setServiceName(instance.getServiceName());
        temp.setEnabled(instance.isEnabled());
        temp.setHealthy(instance.isHealthy());
        temp.setWeight(instance.getWeight());
        temp.setEphemeral(instance.isEphemeral());
        Map<String, String> metaData = new HashMap<>();
        metaData.putAll(instance.getMetadata());
        metaData.put(SkyWalkerConstants.DEST_CLUSTERID_KEY, taskDO.getDestClusterId());
        metaData.put(SkyWalkerConstants.SYNC_SOURCE_KEY,
            skyWalkerCacheServices.getClusterType(taskDO.getSourceClusterId()).getCode());
        metaData.put(SkyWalkerConstants.SOURCE_CLUSTERID_KEY, taskDO.getSourceClusterId());
        temp.setMetadata(metaData);
        return temp;
    }


}
