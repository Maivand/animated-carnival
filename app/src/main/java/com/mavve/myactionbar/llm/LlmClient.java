package com.mavve.myactionbar.llm;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Provider-neutral chat interface. The whole agent stack speaks one dialect —
 * the Anthropic Messages format (content blocks, tool_use / tool_result) —
 * and each client is responsible for translating to and from its provider's
 * wire format. That is what lets the router swap models mid-conversation
 * without the agent loop noticing.
 */
public interface LlmClient {

    /**
     * @param messages Anthropic-format message array (roles user/assistant,
     *                 content either a string or an array of blocks).
     * @param tools    Anthropic-format tool definitions, may be null.
     * @return a normalized object: {"content": [blocks], "stop_reason": "..."}
     */
    JSONObject chat(String model, String system, JSONArray messages, JSONArray tools,
                    int maxTokens) throws Exception;
}
