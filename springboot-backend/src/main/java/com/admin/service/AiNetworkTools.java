package com.admin.service;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only network diagnostics for the operations assistant. Each kind runs a fixed script on the
 * node through the existing terminal channel; targets are validated so they cannot inject shell.
 */
@Service
public class AiNetworkTools {
  static final List<String> KINDS = List.of("ping", "tcp", "trace", "http", "dns");
  private static final Pattern HOST = Pattern.compile("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*\\.?$");
  private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
  private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:]{2,39}(?:%[A-Za-z0-9]{1,15})?$");
  private static final Pattern URL = Pattern.compile("^https?://[A-Za-z0-9.\\-\\[\\]:]{1,260}(?:/[A-Za-z0-9._~%/!$&()*+,;=:@?\\-]{0,500})?$");
  private final JdbcTemplate db;
  private final AiLegacyDiagnostics terminal;
  private final BandwidthTestService bandwidth;

  public AiNetworkTools(JdbcTemplate db, AiLegacyDiagnostics terminal, BandwidthTestService bandwidth) {
    this.db = db;
    this.terminal = terminal;
    this.bandwidth = bandwidth;
  }

  /** Resolves a target node to its public address, so the model can probe between panel nodes. */
  String nodeAddress(long nodeId) {
    for (var row : db.queryForList("SELECT server_ip,ip FROM node WHERE id=?", nodeId)) {
      for (String key : List.of("server_ip", "ip")) {
        String value = Objects.toString(row.get(key), "").trim();
        if (!value.isEmpty()) return value.split(",")[0].trim();
      }
    }
    throw new IllegalArgumentException("目标节点不存在或没有地址");
  }

  Map<String, Object> probe(long node, String kind, String target, Integer port, int timeout) throws Exception {
    String script = script(kind, target, port);
    Map<String, Object> result = new LinkedHashMap<>(terminal.execute(node, script, Math.min(timeout, 90)));
    result.put("output", clean(Objects.toString(result.get("output"), "")));
    result.put("kind", kind);
    result.put("target", target);
    if (port != null) result.put("port", port);
    return result;
  }

  Map<String, Object> speedTest(long source, long target, int seconds) {
    return bandwidth.runAdHoc(source, target, seconds);
  }

  /** Drops the echoed submission and terminal carriage returns before the header line. */
  static String clean(String output) {
    String text = output.replace("\r", "");
    int header = text.indexOf("\nfrom ");
    if (header < 0 && text.startsWith("from ")) header = -1;
    return header >= 0 ? text.substring(header + 1).trim() : text.trim();
  }

  static boolean validHost(String value) {
    if (value == null) return false;
    if (IPV4.matcher(value).matches())
      return Arrays.stream(value.split("\\.")).allMatch(p -> Integer.parseInt(p) <= 255);
    return IPV6.matcher(value).matches() && value.contains(":") || HOST.matcher(value).matches();
  }

  static String script(String kind, String target, Integer port) {
    if (!KINDS.contains(kind)) throw new IllegalArgumentException("不支持的诊断类型：" + kind);
    target = Objects.toString(target, "").trim();
    if (kind.equals("http")) {
      if (!URL.matcher(target).matches() || target.contains("'"))
        throw new IllegalArgumentException("http 诊断需要 http(s):// 开头的有效地址");
    } else if (!validHost(target)) {
      throw new IllegalArgumentException("目标必须是域名、IPv4 或 IPv6 地址");
    }
    if (kind.equals("tcp") && (port == null || port < 1 || port > 65535))
      throw new IllegalArgumentException("tcp 诊断需要 1-65535 的端口");
    String t = "'" + target + "'";
    String header = "echo \"from $(hostname) at $(date -u +%FT%TZ)\"; ";
    return header
        + switch (kind) {
          case "ping" ->
              "if command -v ping >/dev/null 2>&1; then ping -c 8 -i 0.3 -W 2 " + t
                  + " 2>&1 | tail -5; else echo 'ping not installed'; fi";
          case "tcp" ->
              "if ! command -v curl >/dev/null 2>&1; then echo 'curl not installed'; exit 0; fi; for i in 1 2 3 4 5; do"
                  + " c=$(curl -s -o /dev/null --connect-timeout 4 -m 5 -w '%{time_connect}' telnet://"
                  + (target.contains(":") ? "[" + target + "]" : target) + ":" + port
                  + " </dev/null 2>/dev/null); rc=$?; echo \"attempt $i connect=${c}s curl_exit=$rc\"; done;"
                  + " echo 'note: curl_exit 0/7/28 with connect>0 means TCP connected; connect=0 means it did not'";
          case "trace" ->
              "if command -v mtr >/dev/null 2>&1; then timeout 60 mtr -rwbz -c 5 -G 1 " + t
                  + " 2>&1; elif command -v traceroute >/dev/null 2>&1; then timeout 60 traceroute -n -w 2 -q 2 -m 25 " + t
                  + " 2>&1; elif command -v tracepath >/dev/null 2>&1; then timeout 60 tracepath -n -m 25 " + t
                  // Without trace tools, ping with increasing TTL reveals each hop that answers.
                  + " 2>&1; else echo 'trace via ping TTL (mtr/traceroute not installed)'; for ttl in $(seq 1 20); do"
                  + " r=$(ping -n -c 1 -W 1 -t $ttl " + t + " 2>&1); if echo \"$r\" | grep -q 'bytes from'; then"
                  + " echo \"$ttl $(echo \"$r\" | grep -oE 'from [0-9a-fA-F:.]+' | head -1 | cut -d' ' -f2) $(echo \"$r\" | grep -oE 'time=[0-9.]+ ?ms' | head -1) reached\";"
                  + " break; fi; hop=$(echo \"$r\" | grep -oiE 'from [0-9a-fA-F:.]+' | head -1 | cut -d' ' -f2); echo \"$ttl ${hop:-*}\"; done; fi";
          case "http" ->
              "if ! command -v curl >/dev/null 2>&1; then echo 'curl not installed'; exit 0; fi; for i in 1 2 3; do"
                  + " curl -sS -o /dev/null -L --max-redirs 3 --max-time 20 -w 'dns=%{time_namelookup}s"
                  + " connect=%{time_connect}s tls=%{time_appconnect}s ttfb=%{time_starttransfer}s"
                  + " total=%{time_total}s http=%{http_code} ip=%{remote_ip} bytes=%{size_download}"
                  + " speed=%{speed_download}B/s\\n' " + t + " 2>&1; done";
          default ->
              "getent ahosts " + t + " 2>&1 | head -8; if command -v dig >/dev/null 2>&1; then dig +time=2"
                  + " +tries=1 " + t + " A " + t + " AAAA +short 2>&1; elif command -v nslookup >/dev/null 2>&1;"
                  + " then nslookup " + t + " 2>&1 | tail -8; fi; grep -E '^nameserver' /etc/resolv.conf";
        };
  }
}
