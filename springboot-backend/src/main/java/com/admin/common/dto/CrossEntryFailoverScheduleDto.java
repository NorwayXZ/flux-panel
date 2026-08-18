package com.admin.common.dto;

import lombok.Data;

import java.util.List;

/** A Beijing-time preference window for an existing cross-entry member. */
@Data
public class CrossEntryFailoverScheduleDto {
    private Long id;
    private List<Integer> days;
    private String startTime;
    private String endTime;
    private Long preferredForwardId;
    private Boolean enabled = true;
}
