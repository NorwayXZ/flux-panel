package com.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiPanelContextTests {
  @Test
  void catalogAndTopologyDoNotExposeNodeSecretsOrUnselectedRoutes() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:panelcontext;MODE=MySQL;DB_CLOSE_DELAY=-1");
    var db = new JdbcTemplate(source);
    var context = new AiPanelContext(db);
    assertTrue(context.inspect("capabilities", Set.of()).toString().contains("ForwardController"));
    assertThrows(IllegalArgumentException.class, () -> context.inspect("nodes", Set.of()));
    db.execute("CREATE TABLE node(id bigint,name varchar,ip varchar,version varchar,status int,secret varchar)");
    db.update("INSERT INTO node VALUES(1,'selected','127.0.0.1','old',1,'do-not-expose')");
    db.update("INSERT INTO node VALUES(2,'other','127.0.0.2','old',1,'hidden')");
    String nodes = context.inspect("nodes", Set.of(1L)).toString();
    assertTrue(nodes.contains("selected"));
    assertFalse(nodes.contains("other"));
    assertFalse(nodes.contains("do-not-expose"));
    assertThrows(IllegalArgumentException.class, () -> context.inspect("sql", Set.of(1L)));
  }
}
