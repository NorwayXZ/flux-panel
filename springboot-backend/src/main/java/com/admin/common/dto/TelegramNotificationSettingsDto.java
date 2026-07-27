package com.admin.common.dto;

import lombok.Data;

@Data
public class TelegramNotificationSettingsDto {
    private boolean enabled;
    private String botToken;
    private boolean botTokenConfigured;
    private String chatId;
    private boolean nodeEnabled;
    private int nodeRepeatLimit;
    private boolean tunnelEnabled;
    private int tunnelRepeatLimit;
    private boolean forwardEnabled;
    private int forwardRepeatLimit;
    private boolean recoveryEnabled;
    private boolean assetExpiryEnabled;
    private boolean dynamicDnsEnabled;
    private boolean loginOutsideWhitelistEnabled;
    private String loginAllowedCidrs;
    private int repeatIntervalMinutes;
}
