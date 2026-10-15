package com.admin.common.utils;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.junit.jupiter.api.Test;

import java.util.List;

class WebSocketServerAgentJsonTests {
    @Test
    void repeatedValuesAreWrittenInFullForTheAgent() {
        JSONArray rules = new JSONArray();
        for (int id = 1; id <= 3; id++) {
            JSONObject rule = new JSONObject();
            rule.put("id", id);
            rule.put("sourceCidrs", List.of());
            rules.add(rule);
        }
        JSONObject payload = new JSONObject();
        payload.put("rules", rules);
        JSONObject message = new JSONObject();
        message.put("type", "NftForwardApply");
        message.put("data", payload);

        String json = WebSocketServer.agentJson(message);

        assertFalse(json.contains("$ref"), json);
        assertEquals(3, json.split("\"sourceCidrs\":\\[]", -1).length - 1, json);
    }
}
