package com.admin.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import javax.annotation.PostConstruct;

@Component
@DependsOn("servicePublishingSchemaInitializer")
public class OpenWrtDnsSchemaInitializer {
    private final JdbcTemplate jdbc;
    public OpenWrtDnsSchemaInitializer(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS openwrt_dns_resolver (connector_id bigint NOT NULL,user_id int NOT NULL,"
                + "interface_carriers text NOT NULL,smart_entry_group_ids text NOT NULL,policy_revision bigint NOT NULL DEFAULT 1,"
                + "active_interface varchar(64) DEFAULT NULL,active_carrier varchar(24) NOT NULL DEFAULT 'default',"
                + "resolved_queries bigint NOT NULL DEFAULT 0,dnsmasq_reloaded tinyint NOT NULL DEFAULT 0,last_error varchar(500) DEFAULT NULL,"
                + "reported_at bigint DEFAULT NULL,applied_revision bigint NOT NULL DEFAULT 0,status_json text NULL,policy_hash varchar(64) DEFAULT NULL,sync_error varchar(500) DEFAULT NULL,"
                + "created_time bigint NOT NULL,updated_time bigint NOT NULL,PRIMARY KEY(connector_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        for(String[] column:new String[][]{{"status_json","text NULL"},{"applied_revision","bigint NOT NULL DEFAULT 0"},{"policy_hash","varchar(64) DEFAULT NULL"},{"sync_error","varchar(500) DEFAULT NULL"}}){
            Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='openwrt_dns_resolver' AND column_name=?",Integer.class,column[0]);
            if(exists!=null&&exists==0)jdbc.execute("ALTER TABLE openwrt_dns_resolver ADD COLUMN "+column[0]+" "+column[1]);
        }
    }
}
