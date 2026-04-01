/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.service.langfuse;

import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.planner.Plan;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.DB_DIALECT_TYPE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_REVIEW_ENABLED;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.IS_ONLY_NL2SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_EXECUTOR_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_NEXT_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_CURRENT_STEP;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_REPAIR_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_VALIDATION_STATUS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_FALLBACK_MODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_GENERATE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_IS_SUCCESS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_TRIES_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.RESULT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

/**
 * @author zihenzzz
 * @date 2026/2/16 13:54
 */
@Slf4j
@Component
public class LangfuseService {

	private static final int OUTPUT_SUMMARY_LIMIT = 1000;

	private static volatile LangfuseService instance;

	private final Tracer tracer;

	private final boolean enabled;

	private static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("input.value");

	private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("output.value");

	private static final AttributeKey<String> ATTR_AGENT_ID = AttributeKey.stringKey("data_agent.agent_id");

	private static final AttributeKey<String> ATTR_THREAD_ID = AttributeKey.stringKey("data_agent.thread_id");

	private static final AttributeKey<Boolean> ATTR_NL2SQL_ONLY = AttributeKey.booleanKey("data_agent.nl2sql_only");

	private static final AttributeKey<Boolean> ATTR_HUMAN_FEEDBACK = AttributeKey
		.booleanKey("data_agent.human_feedback");

	private static final AttributeKey<String> ATTR_NODE_NAME = AttributeKey.stringKey("data_agent.node_name");

	private static final AttributeKey<Long> ATTR_PLAN_CURRENT_STEP = AttributeKey
		.longKey("data_agent.plan_current_step");

	private static final AttributeKey<Long> ATTR_PLAN_REPAIR_COUNT = AttributeKey
		.longKey("data_agent.plan_repair_count");

	private static final AttributeKey<Long> ATTR_SQL_RETRY_COUNT = AttributeKey
		.longKey("data_agent.sql_generate_retry_count");

	private static final AttributeKey<Long> ATTR_PYTHON_RETRY_COUNT = AttributeKey
		.longKey("data_agent.python_retry_count");

	private static final AttributeKey<String> ATTR_OUTPUT_SUMMARY = AttributeKey
		.stringKey("data_agent.output_summary");

	private static final AttributeKey<String> ATTR_FINAL_STATUS = AttributeKey
		.stringKey("data_agent.final_status");

	private static final AttributeKey<Long> ATTR_PLAN_STEP_COUNT = AttributeKey
		.longKey("data_agent.plan_step_count");

	private static final AttributeKey<String> ATTR_PLAN_TOOLS = AttributeKey
		.stringKey("data_agent.plan_tools");

	private static final AttributeKey<Long> ATTR_RESULT_ROW_COUNT = AttributeKey
		.longKey("data_agent.result_row_count");

	private static final AttributeKey<String> ATTR_DB_DIALECT = AttributeKey
		.stringKey("data_agent.db_dialect");

	private static final AttributeKey<Long> ATTR_SQL_LENGTH = AttributeKey
		.longKey("data_agent.sql_length");

	private static final AttributeKey<String> ATTR_SQL_RETRY_REASON_TYPE = AttributeKey
		.stringKey("data_agent.sql_retry_reason_type");

	private static final AttributeKey<Long> ATTR_PYTHON_CODE_LENGTH = AttributeKey
		.longKey("data_agent.python_code_length");

	private static final AttributeKey<Boolean> ATTR_PYTHON_SUCCESS = AttributeKey
		.booleanKey("data_agent.python_success");

	private static final AttributeKey<Boolean> ATTR_PYTHON_FALLBACK = AttributeKey
		.booleanKey("data_agent.python_fallback_mode");

	private static final AttributeKey<Long> ATTR_REPORT_LENGTH = AttributeKey
		.longKey("data_agent.report_length");

	private static final AttributeKey<String> ATTR_DECISION = AttributeKey
		.stringKey("data_agent.decision");

	private static final AttributeKey<String> ATTR_NEXT_NODE = AttributeKey
		.stringKey("data_agent.next_node");

	private static final AttributeKey<Boolean> ATTR_PLAN_VALIDATION_STATUS = AttributeKey
		.booleanKey("data_agent.plan_validation_status");

	private static final AttributeKey<String> ATTR_ROUTE_NEXT_NODE = AttributeKey
		.stringKey("data_agent.route_next_node");

	private static final AttributeKey<String> ATTR_TOOL_TO_USE = AttributeKey
		.stringKey("data_agent.tool_to_use");

	private static final AttributeKey<Boolean> ATTR_PLAN_COMPLETED = AttributeKey
		.booleanKey("data_agent.plan_completed");

	private static final AttributeKey<Long> GEN_AI_PROMPT_TOKENS = AttributeKey.longKey("gen_ai.usage.prompt_tokens");

	private static final AttributeKey<Long> GEN_AI_COMPLETION_TOKENS = AttributeKey
		.longKey("gen_ai.usage.completion_tokens");

	private static final AttributeKey<Long> GEN_AI_TOTAL_TOKENS = AttributeKey.longKey("gen_ai.usage.total_tokens");

	private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

	private static final AttributeKey<String> ERROR_MESSAGE = AttributeKey.stringKey("error.message");

	private static final ConcurrentHashMap<String, Span> GRAPH_SPANS = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, long[]> GRAPH_TOKEN_ACCUMULATOR = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, long[]> NODE_TOKEN_ACCUMULATOR = new ConcurrentHashMap<>();

	public LangfuseService(Tracer langfuseTracer, @Value("${langfuse.enabled:true}") boolean enabled) {
		this.tracer = langfuseTracer;
		this.enabled = enabled;
		instance = this;
	}

	public Span startLLMSpan(String spanName, GraphRequest request) {
		if (!enabled) {
			return Span.getInvalid();
		}

		try {
			Span span = tracer.spanBuilder(spanName)
				.setSpanKind(SpanKind.CLIENT)
				.setParent(Context.current())
				.startSpan();

			String inputValue = String.format(
					"{\"query\":\"%s\",\"agentId\":\"%s\",\"threadId\":\"%s\",\"nl2sqlOnly\":%s,\"humanFeedback\":%s}",
					request.getQuery() != null ? request.getQuery() : "",
					request.getAgentId() != null ? request.getAgentId() : "",
					request.getThreadId() != null ? request.getThreadId() : "", request.isNl2sqlOnly(),
					request.isHumanFeedback());
			span.setAttribute(INPUT_VALUE, inputValue);
			span.setAttribute(ATTR_AGENT_ID, request.getAgentId() != null ? request.getAgentId() : "");
			span.setAttribute(ATTR_THREAD_ID, request.getThreadId() != null ? request.getThreadId() : "");
			span.setAttribute(ATTR_NL2SQL_ONLY, request.isNl2sqlOnly());
			span.setAttribute(ATTR_HUMAN_FEEDBACK, request.isHumanFeedback());

			if (request.getThreadId() != null) {
				GRAPH_SPANS.put(request.getThreadId(), span);
				GRAPH_TOKEN_ACCUMULATOR.put(request.getThreadId(), new long[] { 0, 0 });
			}

			return span;
		}
		catch (Exception e) {
			log.error("Failed to start OTel span", e);
			return Span.getInvalid();
		}
	}

	public static Span startNodeSpan(String nodeName, OverAllState state) {
		LangfuseService service = instance;
		if (service == null || !service.enabled) {
			return Span.getInvalid();
		}

		try {
			String threadId = getStringStateValue(state, TRACE_THREAD_ID);
			Span parentSpan = threadId != null ? GRAPH_SPANS.get(threadId) : null;
			Context parentContext = parentSpan != null ? Context.root().with(parentSpan) : Context.current();

			Span span = service.tracer.spanBuilder(nodeName)
				.setSpanKind(SpanKind.INTERNAL)
				.setParent(parentContext)
				.startSpan();
			populateNodeAttributes(span, nodeName, state);
			return span;
		}
		catch (Exception e) {
			log.error("Failed to start node span for {}", nodeName, e);
			return Span.getInvalid();
		}
	}

	public static void accumulateTokens(Object threadId, String nodeName, long promptTokens, long completionTokens) {
		if (threadId == null) {
			return;
		}

		String threadKey = threadId.toString();
		accumulateTokenPair(GRAPH_TOKEN_ACCUMULATOR.get(threadKey), promptTokens, completionTokens);
		if (StringUtils.isNotBlank(nodeName)) {
			long[] nodeTokens = NODE_TOKEN_ACCUMULATOR.computeIfAbsent(nodeTokenKey(threadKey, nodeName),
					key -> new long[] { 0, 0 });
			accumulateTokenPair(nodeTokens, promptTokens, completionTokens);
		}
	}

	public static void accumulateTokens(Object threadId, long promptTokens, long completionTokens) {
		accumulateTokens(threadId, null, promptTokens, completionTokens);
	}

	public void endSpanSuccess(Span span, String threadId, String output) {
		if (!enabled || span == null || !span.isRecording()) {
			return;
		}

		try {
			span.setAttribute(OUTPUT_VALUE, output != null ? output : "");
			applyAccumulatedGraphTokens(span, threadId);
			span.setStatus(StatusCode.OK);
		}
		catch (Exception e) {
			log.error("Failed to end OTel span", e);
		}
		finally {
			if (threadId != null) {
				GRAPH_SPANS.remove(threadId);
			}
			span.end();
		}
	}

	public void endSpanError(Span span, String threadId, Exception error) {
		if (!enabled || span == null || !span.isRecording()) {
			return;
		}

		try {
			String errorType = error.getClass().getSimpleName();
			String errorMessage = error.getMessage() != null ? error.getMessage() : "";

			span.setAttribute(ERROR_TYPE, errorType);
			span.setAttribute(ERROR_MESSAGE, abbreviate(errorMessage));
			applyAccumulatedGraphTokens(span, threadId);
			span.setStatus(StatusCode.ERROR, errorType + ": " + errorMessage);
			span.recordException(error);
		}
		catch (Exception e) {
			log.error("Failed to record span error", e);
		}
		finally {
			if (threadId != null) {
				GRAPH_SPANS.remove(threadId);
			}
			span.end();
		}
	}

	public static void endNodeSpanSuccess(Span span, OverAllState state, String nodeName, String outputSummary,
			Map<String, Object> resultData) {
		LangfuseService service = instance;
		if (service == null || !service.enabled || span == null || !span.isRecording()) {
			return;
		}

		try {
			if (StringUtils.isNotBlank(outputSummary)) {
				span.setAttribute(ATTR_OUTPUT_SUMMARY, abbreviate(outputSummary));
			}
			populateNodeSpecificAttributes(span, nodeName, state, resultData);
			applyAccumulatedNodeTokens(span, getStringStateValue(state, TRACE_THREAD_ID), nodeName);
			span.setAttribute(ATTR_FINAL_STATUS, "success");
			span.setStatus(StatusCode.OK);
		}
		catch (Exception e) {
			log.error("Failed to finish node span for {}", nodeName, e);
		}
		finally {
			span.end();
		}
	}

	public static void endNodeSpanSuccess(Span span, OverAllState state, String nodeName, String outputSummary) {
		endNodeSpanSuccess(span, state, nodeName, outputSummary, null);
	}

	public static void endNodeSpanError(Span span, OverAllState state, String nodeName, Throwable error) {
		LangfuseService service = instance;
		if (service == null || !service.enabled || span == null || !span.isRecording()) {
			return;
		}

		try {
			String errorType = error.getClass().getSimpleName();
			String errorMessage = error.getMessage() != null ? error.getMessage() : "";
			span.setAttribute(ERROR_TYPE, errorType);
			span.setAttribute(ERROR_MESSAGE, abbreviate(errorMessage));
			applyAccumulatedNodeTokens(span, getStringStateValue(state, TRACE_THREAD_ID), nodeName);
			span.setAttribute(ATTR_FINAL_STATUS, "error");
			span.setStatus(StatusCode.ERROR, errorType + ": " + errorMessage);
			span.recordException(error);
		}
		catch (Exception e) {
			log.error("Failed to record node span error for {}", nodeName, e);
		}
		finally {
			span.end();
		}
	}

	public static void endNodeSpanCancelled(Span span, OverAllState state, String nodeName) {
		LangfuseService service = instance;
		if (service == null || !service.enabled || span == null || !span.isRecording()) {
			return;
		}

		try {
			applyAccumulatedNodeTokens(span, getStringStateValue(state, TRACE_THREAD_ID), nodeName);
			span.setAttribute(ATTR_FINAL_STATUS, "cancelled");
		}
		catch (Exception e) {
			log.error("Failed to finish cancelled node span for {}", nodeName, e);
		}
		finally {
			span.end();
		}
	}

	private void applyAccumulatedGraphTokens(Span span, String threadId) {
		if (threadId == null) {
			return;
		}
		long[] tokens = GRAPH_TOKEN_ACCUMULATOR.remove(threadId);
		applyTokenAttributes(span, tokens);
	}

	private static void applyAccumulatedNodeTokens(Span span, String threadId, String nodeName) {
		if (threadId == null || StringUtils.isBlank(nodeName)) {
			return;
		}
		long[] tokens = NODE_TOKEN_ACCUMULATOR.remove(nodeTokenKey(threadId, nodeName));
		applyTokenAttributes(span, tokens);
	}

	private static void applyTokenAttributes(Span span, long[] tokens) {
		if (tokens == null) {
			return;
		}
		synchronized (tokens) {
			if (tokens[0] > 0 || tokens[1] > 0) {
				span.setAttribute(GEN_AI_PROMPT_TOKENS, tokens[0]);
				span.setAttribute(GEN_AI_COMPLETION_TOKENS, tokens[1]);
				span.setAttribute(GEN_AI_TOTAL_TOKENS, tokens[0] + tokens[1]);
			}
		}
	}

	private static void populateNodeAttributes(Span span, String nodeName, OverAllState state) {
		span.setAttribute(ATTR_NODE_NAME, nodeName);

		String agentId = getStringStateValue(state, AGENT_ID);
		if (agentId != null) {
			span.setAttribute(ATTR_AGENT_ID, agentId);
		}

		String threadId = getStringStateValue(state, TRACE_THREAD_ID);
		if (threadId != null) {
			span.setAttribute(ATTR_THREAD_ID, threadId);
		}

		Boolean nl2sqlOnly = getBooleanStateValue(state, IS_ONLY_NL2SQL);
		if (nl2sqlOnly != null) {
			span.setAttribute(ATTR_NL2SQL_ONLY, nl2sqlOnly);
		}

		Boolean humanFeedback = getBooleanStateValue(state, HUMAN_REVIEW_ENABLED);
		if (humanFeedback != null) {
			span.setAttribute(ATTR_HUMAN_FEEDBACK, humanFeedback);
		}

		Integer currentStep = getIntegerStateValue(state, PLAN_CURRENT_STEP);
		if (currentStep != null) {
			span.setAttribute(ATTR_PLAN_CURRENT_STEP, currentStep.longValue());
		}

		Integer repairCount = getIntegerStateValue(state, PLAN_REPAIR_COUNT);
		if (repairCount != null) {
			span.setAttribute(ATTR_PLAN_REPAIR_COUNT, repairCount.longValue());
		}

		Integer sqlRetryCount = getIntegerStateValue(state, SQL_GENERATE_COUNT);
		if (sqlRetryCount != null) {
			span.setAttribute(ATTR_SQL_RETRY_COUNT, sqlRetryCount.longValue());
		}

		Integer pythonRetryCount = getIntegerStateValue(state, PYTHON_TRIES_COUNT);
		if (pythonRetryCount != null) {
			span.setAttribute(ATTR_PYTHON_RETRY_COUNT, pythonRetryCount.longValue());
		}
	}

	private static void populateNodeSpecificAttributes(Span span, String nodeName, OverAllState state,
			Map<String, Object> resultData) {
		if (resultData == null || resultData.isEmpty()) {
			return;
		}

		switch (nodeName) {
			case "PlannerNode" -> populatePlannerAttributes(span, resultData);
			case "SqlExecuteNode" -> populateSqlExecuteAttributes(span, state, resultData);
			case "SqlGenerateNode" -> populateSqlGenerateAttributes(span, resultData);
			case "PythonGenerateNode" -> populatePythonGenerateAttributes(span, resultData);
			case "PythonExecuteNode" -> populatePythonExecuteAttributes(span, resultData);
			case "ReportGeneratorNode" -> populateReportGeneratorAttributes(span, resultData);
			case "HumanFeedbackNode" -> populateHumanFeedbackAttributes(span, resultData);
			case "PlanExecutorNode" -> populatePlanExecutorAttributes(span, state, resultData);
			default -> {
			}
		}
	}

	private static void populatePlannerAttributes(Span span, Map<String, Object> resultData) {
		Object plannerOutput = resultData.get(PLANNER_NODE_OUTPUT);
		if (!(plannerOutput instanceof String plannerJson) || StringUtils.isBlank(plannerJson)) {
			return;
		}

		try {
			Plan plan = JsonUtil.getObjectMapper().readValue(plannerJson, Plan.class);
			if (plan.getExecutionPlan() != null) {
				span.setAttribute(ATTR_PLAN_STEP_COUNT, (long) plan.getExecutionPlan().size());
				String tools = plan.getExecutionPlan()
					.stream()
					.map(step -> step.getToolToUse() == null ? "" : step.getToolToUse())
					.filter(StringUtils::isNotBlank)
					.distinct()
					.reduce((left, right) -> left + "," + right)
					.orElse("");
				if (StringUtils.isNotBlank(tools)) {
					span.setAttribute(ATTR_PLAN_TOOLS, tools);
				}
			}
		}
		catch (Exception e) {
			log.debug("Failed to parse planner output for node attributes", e);
		}
	}

	private static void populateSqlExecuteAttributes(Span span, OverAllState state, Map<String, Object> resultData) {
		Object rows = resultData.get(SQL_RESULT_LIST_MEMORY);
		if (rows instanceof java.util.List<?> rowList) {
			span.setAttribute(ATTR_RESULT_ROW_COUNT, (long) rowList.size());
		}

		String dialect = getStringStateValue(state, DB_DIALECT_TYPE);
		if (StringUtils.isNotBlank(dialect)) {
			span.setAttribute(ATTR_DB_DIALECT, dialect);
		}
	}

	private static void populateSqlGenerateAttributes(Span span, Map<String, Object> resultData) {
		Object sql = resultData.get(SQL_GENERATE_OUTPUT);
		if (sql instanceof String sqlText && StringUtils.isNotBlank(sqlText)) {
			span.setAttribute(ATTR_SQL_LENGTH, (long) sqlText.length());
		}

		Object retryReason = resultData.get(SQL_REGENERATE_REASON);
		if (retryReason instanceof SqlRetryDto sqlRetryDto) {
			if (sqlRetryDto.semanticFail()) {
				span.setAttribute(ATTR_SQL_RETRY_REASON_TYPE, "semantic");
			}
			else if (sqlRetryDto.sqlExecuteFail()) {
				span.setAttribute(ATTR_SQL_RETRY_REASON_TYPE, "sql_execute");
			}
		}
	}

	private static void populatePythonGenerateAttributes(Span span, Map<String, Object> resultData) {
		Object pythonCode = resultData.get(PYTHON_GENERATE_NODE_OUTPUT);
		if (pythonCode instanceof String code && StringUtils.isNotBlank(code)) {
			span.setAttribute(ATTR_PYTHON_CODE_LENGTH, (long) code.length());
		}
	}

	private static void populatePythonExecuteAttributes(Span span, Map<String, Object> resultData) {
		Object success = resultData.get(PYTHON_IS_SUCCESS);
		if (success instanceof Boolean successValue) {
			span.setAttribute(ATTR_PYTHON_SUCCESS, successValue);
		}

		Object fallback = resultData.get(PYTHON_FALLBACK_MODE);
		if (fallback instanceof Boolean fallbackValue) {
			span.setAttribute(ATTR_PYTHON_FALLBACK, fallbackValue);
		}
	}

	private static void populateReportGeneratorAttributes(Span span, Map<String, Object> resultData) {
		Object report = resultData.get(RESULT);
		if (report instanceof String reportContent && StringUtils.isNotBlank(reportContent)) {
			span.setAttribute(ATTR_REPORT_LENGTH, (long) reportContent.length());
		}
	}

	private static void populateHumanFeedbackAttributes(Span span, Map<String, Object> resultData) {
		String nextNode = getStringResultValue(resultData, "human_next_node");
		if (StringUtils.isNotBlank(nextNode)) {
			span.setAttribute(ATTR_NEXT_NODE, nextNode);
			span.setAttribute(ATTR_DECISION, resolveHumanDecision(nextNode));
		}
	}

	private static void populatePlanExecutorAttributes(Span span, OverAllState state, Map<String, Object> resultData) {
		Boolean validationStatus = getBooleanResultValue(resultData, PLAN_VALIDATION_STATUS);
		if (validationStatus != null) {
			span.setAttribute(ATTR_PLAN_VALIDATION_STATUS, validationStatus);
		}

		String nextNode = getStringResultValue(resultData, PLAN_NEXT_NODE);
		if (StringUtils.isNotBlank(nextNode)) {
			span.setAttribute(ATTR_ROUTE_NEXT_NODE, nextNode);
		}

		String toolToUse = resolvePlanExecutorToolToUse(state, nextNode);
		if (StringUtils.isNotBlank(toolToUse)) {
			span.setAttribute(ATTR_TOOL_TO_USE, toolToUse);
		}

		if (StringUtils.isNotBlank(nextNode)) {
			span.setAttribute(ATTR_PLAN_COMPLETED, isPlanCompletionRoute(state, nextNode));
		}
	}

	private static String resolveHumanDecision(String nextNode) {
		return switch (nextNode) {
			case "WAIT_FOR_FEEDBACK" -> "waiting";
			case "END" -> "max_repair_exceeded";
			case PLAN_EXECUTOR_NODE -> "approved";
			default -> "rejected";
		};
	}

	private static String resolvePlanExecutorToolToUse(OverAllState state, String nextNode) {
		if (StringUtils.isBlank(nextNode)) {
			return null;
		}
		if ("END".equals(nextNode)) {
			return "END";
		}
		if ("HUMAN_FEEDBACK_NODE".equals(nextNode)) {
			return "HUMAN_FEEDBACK_NODE";
		}
		return nextNode;
	}

	private static boolean isPlanCompletionRoute(OverAllState state, String nextNode) {
		Boolean onlyNl2sql = getBooleanStateValue(state, IS_ONLY_NL2SQL);
		if (Boolean.TRUE.equals(onlyNl2sql)) {
			return "END".equals(nextNode);
		}
		return "REPORT_GENERATOR_NODE".equals(nextNode) || "END".equals(nextNode);
	}

	private static void accumulateTokenPair(long[] tokens, long promptTokens, long completionTokens) {
		if (tokens == null) {
			return;
		}
		synchronized (tokens) {
			tokens[0] += promptTokens;
			tokens[1] += completionTokens;
		}
	}

	private static String getStringStateValue(OverAllState state, String key) {
		return state.value(key).map(String.class::cast).orElse(null);
	}

	private static Boolean getBooleanStateValue(OverAllState state, String key) {
		return state.value(key).map(Boolean.class::cast).orElse(null);
	}

	private static String getStringResultValue(Map<String, Object> resultData, String key) {
		Object value = resultData.get(key);
		return value == null ? null : value.toString();
	}

	private static Boolean getBooleanResultValue(Map<String, Object> resultData, String key) {
		Object value = resultData.get(key);
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		if (value instanceof String stringValue && StringUtils.isNotBlank(stringValue)) {
			return Boolean.parseBoolean(stringValue);
		}
		return null;
	}

	private static Integer getIntegerStateValue(OverAllState state, String key) {
		return state.value(key).map(Integer.class::cast).orElse(null);
	}

	private static String nodeTokenKey(String threadId, String nodeName) {
		return threadId + "::" + nodeName;
	}

	private static String abbreviate(String text) {
		return text == null ? "" : StringUtils.abbreviate(text, OUTPUT_SUMMARY_LIMIT);
	}

}
