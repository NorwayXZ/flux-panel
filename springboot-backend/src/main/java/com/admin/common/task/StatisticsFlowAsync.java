package com.admin.common.task;


import com.admin.entity.StatisticsFlow;
import com.admin.entity.User;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Configuration
@EnableScheduling
public class StatisticsFlowAsync {

    @Resource
    UserService userService;

    @Resource
    StatisticsFlowService statisticsFlowService;

    @Scheduled(cron = "0 0 * * * ?")
    public void statistics_flow() {
        LocalDateTime currentHour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        String hourString = currentHour.format(DateTimeFormatter.ofPattern("HH:mm"));
        long time = new Date().getTime();

        // Keep eight days so the dashboard can show the last seven full days.
        long nowMs = new Date().getTime();
        long cutoffMs = nowMs - 8L * 24 * 60 * 60 * 1000;
        statisticsFlowService.remove(
                new LambdaQueryWrapper<StatisticsFlow>()
                        .lt(StatisticsFlow::getCreatedTime, cutoffMs)
        );





        List<User> list = userService.list();
        List<StatisticsFlow> statisticsFlowList = new ArrayList<>();

        for (User user : list) {
            long currentFlow = user.getInFlow() + user.getOutFlow();

            // 从数据库获取上一次记录
            StatisticsFlow lastFlowRecord = statisticsFlowService.getOne(
                    new LambdaQueryWrapper<StatisticsFlow>()
                            .eq(StatisticsFlow::getUserId, user.getId()) 
                            .orderByDesc(StatisticsFlow::getId)         
                            .last("LIMIT 1")                     
            );

            long currentTotalFlow = currentFlow;
            long incrementFlow = hourlyIncrement(
                    lastFlowRecord == null ? null : lastFlowRecord.getTotalFlow(), currentTotalFlow);
            long currentIn = user.getInFlow();
            long currentOut = user.getOutFlow();

            StatisticsFlow statisticsFlow = new StatisticsFlow();
            statisticsFlow.setUserId(user.getId());
            statisticsFlow.setFlow(incrementFlow);
            statisticsFlow.setInFlow(hourlyIncrement(
                    lastFlowRecord == null ? null : lastFlowRecord.getTotalInFlow(), currentIn));
            statisticsFlow.setOutFlow(hourlyIncrement(
                    lastFlowRecord == null ? null : lastFlowRecord.getTotalOutFlow(), currentOut));
            statisticsFlow.setTotalFlow(currentTotalFlow);
            statisticsFlow.setTotalInFlow(currentIn);
            statisticsFlow.setTotalOutFlow(currentOut);
            statisticsFlow.setTime(hourString);
            statisticsFlow.setCreatedTime(time);

            statisticsFlowList.add(statisticsFlow);
        }

        statisticsFlowService.saveBatch(statisticsFlowList);
    }


    /**
     * Traffic used since the previous hourly sample.
     *
     * The first sample of an account has nothing to compare with, so it records
     * zero rather than the account's whole lifetime total. A total lower than the
     * previous one means the counter was reset; usage since the reset is the
     * current total.
     */
    static long hourlyIncrement(Long previousTotal, long currentTotal) {
        if (previousTotal == null) {
            return 0L;
        }
        long increment = currentTotal - previousTotal;
        return increment < 0 ? currentTotal : increment;
    }
}
