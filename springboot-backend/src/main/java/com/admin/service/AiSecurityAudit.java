package com.admin.service;

import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only security inspection of a node through the terminal channel. */
@Service
public class AiSecurityAudit {
  private final AiLegacyDiagnostics terminal;

  public AiSecurityAudit(AiLegacyDiagnostics terminal) {
    this.terminal = terminal;
  }

  static final String SCRIPT = String.join("\n",
      "echo \"from $(hostname) at $(date -u +%FT%TZ)\"",
      "section() { printf '\\n=== %s ===\\n' \"$1\"; }",
      "section SYSTEM; uname -srm; . /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\"; uptime",
      "[ -f /var/run/reboot-required ] && echo 'reboot-required: yes' || true",
      "section SSHD",
      "if command -v sshd >/dev/null 2>&1; then sshd -T 2>/dev/null | grep -Ei '^(port|permitrootlogin|passwordauthentication|pubkeyauthentication|permitemptypasswords|maxauthtries|x11forwarding|allowusers|kbdinteractiveauthentication) '; else echo 'sshd not found'; fi",
      "section LISTENING",
      "if command -v ss >/dev/null 2>&1; then ss -H -tulpn 2>/dev/null | awk '{print $1, $5, $7}' | sort -u | head -60; else netstat -tulpn 2>/dev/null | tail -n +3 | head -60; fi",
      "section FAILED_LOGINS_7D",
      "( journalctl -q --since '-7 days' -u ssh -u sshd 2>/dev/null; cat /var/log/auth.log /var/log/secure 2>/dev/null ) | grep -E 'Failed password|Invalid user' > /tmp/.ai-sec-fails 2>/dev/null || true",
      "echo \"count: $(wc -l < /tmp/.ai-sec-fails 2>/dev/null || echo 0)\"",
      "grep -oE 'from [0-9a-fA-F:.]+' /tmp/.ai-sec-fails 2>/dev/null | sort | uniq -c | sort -rn | head -5; rm -f /tmp/.ai-sec-fails",
      "section SUCCESSFUL_ROOT_LOGINS",
      "last -n 5 root 2>/dev/null | head -5",
      "section FAIL2BAN",
      "if command -v fail2ban-client >/dev/null 2>&1; then fail2ban-client status 2>&1 | head -5; else echo 'fail2ban not installed'; fi",
      "section FIREWALL",
      "if command -v ufw >/dev/null 2>&1; then ufw status 2>&1 | head -12; fi",
      "if command -v nft >/dev/null 2>&1; then echo \"nft rules: $(nft list ruleset 2>/dev/null | grep -c '^\\s*[a-z].*\\(accept\\|drop\\|reject\\)')\"; fi",
      "if command -v iptables >/dev/null 2>&1; then echo \"iptables INPUT policy: $(iptables -S INPUT 2>/dev/null | head -1)\"; fi",
      "section UPDATES",
      "if command -v apt-get >/dev/null 2>&1; then apt list --upgradable 2>/dev/null | tail -n +2 > /tmp/.ai-sec-up; echo \"upgradable: $(wc -l < /tmp/.ai-sec-up)\"; echo \"security: $(grep -ci security /tmp/.ai-sec-up)\"; grep -i security /tmp/.ai-sec-up | head -8; rm -f /tmp/.ai-sec-up;",
      "elif command -v dnf >/dev/null 2>&1; then dnf -q updateinfo list --security 2>/dev/null | head -10; echo \"security: $(dnf -q updateinfo list --security 2>/dev/null | wc -l)\";",
      "elif command -v apk >/dev/null 2>&1; then echo \"upgradable: $(apk version -l '<' 2>/dev/null | tail -n +2 | wc -l)\"; else echo 'package manager not recognized'; fi",
      "section USERS_WITH_SHELL",
      "awk -F: '$7 !~ /(nologin|false|sync|halt|shutdown)$/ {print $1\":\"$3}' /etc/passwd",
      "section AUTHORIZED_KEYS",
      "for f in /root/.ssh/authorized_keys /home/*/.ssh/authorized_keys; do [ -f \"$f\" ] && echo \"$f: $(grep -cE '^(ssh|ecdsa|sk-)' \"$f\") keys\"; done 2>/dev/null; true");

  Map<String, Object> audit(long node, int timeout) throws Exception {
    Map<String, Object> result = new LinkedHashMap<>(terminal.execute(node, SCRIPT, Math.min(timeout, 90)));
    result.put("output", AiNetworkTools.clean(Objects.toString(result.get("output"), "")));
    result.put("guidance", "按风险排序给出加固建议：密码/root 登录、对公网开放的高危端口、暴力破解来源、缺失的 fail2ban/防火墙、"
        + "待装安全更新、需要重启的内核。只读检查，未修改任何配置；修改 SSH 前提醒确认已有可用密钥，避免把自己锁在外面。");
    return result;
  }
}
