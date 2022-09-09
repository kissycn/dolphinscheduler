/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.master.runner.task;

import com.google.auto.service.AutoService;
import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.BlockingOpportunity;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.NetUtils;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.plugin.task.api.enums.DependResult;
import org.apache.dolphinscheduler.plugin.task.api.enums.ExecutionStatus;
import org.apache.dolphinscheduler.plugin.task.api.model.DependentItem;
import org.apache.dolphinscheduler.plugin.task.api.model.DependentTaskModel;
import org.apache.dolphinscheduler.plugin.task.api.parameters.BlockingParameters;
import org.apache.dolphinscheduler.plugin.task.api.parameters.DependentParameters;
import org.apache.dolphinscheduler.plugin.task.api.utils.DependentUtils;
import org.apache.dolphinscheduler.server.utils.LogUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dolphinscheduler.plugin.task.api.TaskConstants.TASK_TYPE_BLOCKING;

/**
 * blocking task processor
 */
@AutoService(ITaskProcessor.class)
public class BlockingTaskProcessor extends BaseTaskProcessor {

    /**
     * dependent parameters
     */
    private DependentParameters dependentParameters;

    /**
     * condition result
     */
    private DependResult conditionResult = DependResult.WAITING;

    /**
     * blocking parameters
     */
    private BlockingParameters blockingParam;

    /**
     * complete task map
     */
    private Map<Long, ExecutionStatus> completeTaskList = new ConcurrentHashMap<>();

    private void initTaskParameters() {
        this.dependentParameters = taskInstance.getDependency();
        this.blockingParam = JSONUtils.parseObject(taskInstance.getTaskParams(), BlockingParameters.class);
    }

    @Override
    protected boolean pauseTask() {
        taskInstance.setState(ExecutionStatus.PAUSE);
        taskInstance.setEndTime(new Date());
        processService.saveTaskInstance(taskInstance);
        logger.info("blocking task has been paused");
        return true;
    }

    @Override
    protected boolean killTask() {
        taskInstance.setState(ExecutionStatus.KILL);
        taskInstance.setEndTime(new Date());
        processService.saveTaskInstance(taskInstance);
        logger.info("blocking task has been killed");
        return true;
    }

    @Override
    protected boolean taskTimeout() {
        return true;
    }

    @Override
    protected boolean runTask() {
        logger.info("blocking task starting");
        initTaskParameters();
        if (conditionResult.equals(DependResult.WAITING)) {
            setConditionResult();
            endTask();
        } else {
            endTask();
        }
        logger.info("blocking task finished");
        return true;
    }

    @Override
    protected boolean dispatchTask() {
        return false;
    }

    @Override
    public String getType() {
        return TASK_TYPE_BLOCKING;
    }

    /**
     * depend result for depend item
     */
    private DependResult getDependResultForItem(DependentItem item) {

        DependResult dependResult = DependResult.SUCCESS;
        if (!completeTaskList.containsKey(item.getDepTaskCode())) {
            logger.info("depend item: {} have not completed yet.", item.getDepTaskCode());
            dependResult = DependResult.FAILED;
            return dependResult;
        }
        ExecutionStatus executionStatus = completeTaskList.get(item.getDepTaskCode());
        if (executionStatus != item.getStatus()) {
            logger.info("depend item : {} expect status: {}, actual status: {}", item.getDepTaskCode(),
                    item.getStatus(), executionStatus);
            dependResult = DependResult.FAILED;
        }
        logger.info("dependent item complete {} {},{}",
                Constants.DEPENDENT_SPLIT, item.getDepTaskCode(), dependResult);
        return dependResult;
    }

    private void setConditionResult() {

        List<TaskInstance> taskInstances = processService
                .findValidTaskListByProcessId(taskInstance.getProcessInstanceId());
        for (TaskInstance task : taskInstances) {
            completeTaskList.putIfAbsent(task.getTaskCode(), task.getState());
        }

        List<DependResult> tempResultList = new ArrayList<>();
        for (DependentTaskModel dependentTaskModel : dependentParameters.getDependTaskList()) {
            List<DependResult> itemDependResult = new ArrayList<>();
            for (DependentItem item : dependentTaskModel.getDependItemList()) {
                itemDependResult.add(getDependResultForItem(item));
            }
            DependResult tempResult =
                    DependentUtils.getDependResultForRelation(dependentTaskModel.getRelation(), itemDependResult);
            tempResultList.add(tempResult);
        }
        conditionResult = DependentUtils.getDependResultForRelation(dependentParameters.getRelation(), tempResultList);
        logger.info("the blocking task depend result : {}", conditionResult);
    }

    private void endTask() {
        ExecutionStatus status = ExecutionStatus.SUCCESS;
        DependResult expected = this.blockingParam.getBlockingOpportunity()
                .equals(BlockingOpportunity.BLOCKING_ON_SUCCESS.getDesc())
                        ? DependResult.SUCCESS
                        : DependResult.FAILED;
        boolean isBlocked = (expected == this.conditionResult);
        logger.info("blocking opportunity: expected-->{}, actual-->{}", expected, this.conditionResult);
        processInstance.setBlocked(isBlocked);
        if (isBlocked) {
            processInstance.setStateWithDesc(ExecutionStatus.READY_BLOCK, "ready block");
        }
        taskInstance.setState(status);
        taskInstance.setEndTime(new Date());
        processService.updateTaskInstance(taskInstance);
        logger.info("blocking task execute complete, blocking:{}", isBlocked);
    }
}
