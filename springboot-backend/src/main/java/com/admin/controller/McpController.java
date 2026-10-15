package com.admin.controller;

import com.admin.service.McpService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MCP endpoint (Streamable HTTP). Authenticate with "Authorization: Bearer cn_..." API token. */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/mcp")
public class McpController {
    private final McpService mcp;

    public McpController(McpService mcp) {
        this.mcp = mcp;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> post(@RequestBody String body) {
        Object parsed;
        try {
            parsed = JSON.parse(body);
        } catch (RuntimeException e) {
            return ResponseEntity.ok(McpService.error(null, -32700, "Parse error").toJSONString());
        }
        if (parsed instanceof JSONArray batch) {
            JSONArray responses = new JSONArray();
            for (Object item : batch) {
                if (item instanceof JSONObject message) {
                    JSONObject response = mcp.handle(message);
                    if (response != null) responses.add(response);
                }
            }
            return responses.isEmpty() ? ResponseEntity.accepted().build() : ResponseEntity.ok(responses.toJSONString());
        }
        if (!(parsed instanceof JSONObject message)) {
            return ResponseEntity.ok(McpService.error(null, -32600, "Invalid Request").toJSONString());
        }
        JSONObject response = mcp.handle(message);
        return response == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(response.toJSONString());
    }

    /** This stateless server does not open server-initiated streams or sessions. */
    @GetMapping
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> close() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
