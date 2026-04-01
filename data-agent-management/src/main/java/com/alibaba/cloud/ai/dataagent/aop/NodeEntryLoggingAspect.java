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
package com.alibaba.cloud.ai.dataagent.aop;

import com.alibaba.cloud.ai.dataagent.service.langfuse.LangfuseService;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Records node entry logs and captures node-level observations for synchronous nodes.
 */
@Aspect
@Component
@Slf4j
public class NodeEntryLoggingAspect {

	private static final Set<String> SYNCHRONOUS_NODE_NAMES = Set.of("PlanExecutorNode", "HumanFeedbackNode");

	@Pointcut("execution(* com.alibaba.cloud.ai.dataagent.workflow.node..*.apply(com.alibaba.cloud.ai.graph.OverAllState))")
	public void nodeEntry() {
	}

	@Around("nodeEntry()")
	public Object logNodeEntry(ProceedingJoinPoint joinPoint) throws Throwable {
		String nodeName = joinPoint.getTarget().getClass().getSimpleName();
		Object[] args = joinPoint.getArgs();
		OverAllState state = args != null && args.length > 0 && args[0] instanceof OverAllState overallState
				? overallState : null;

		log.info("Entering {} node", nodeName);
		if (state != null) {
			log.debug("State: {}", state);
		}

		if (state == null || !SYNCHRONOUS_NODE_NAMES.contains(nodeName)) {
			return joinPoint.proceed();
		}

		Span nodeSpan = LangfuseService.startNodeSpan(nodeName, state);
		try {
			Object result = joinPoint.proceed();
			LangfuseService.endNodeSpanSuccess(nodeSpan, state, nodeName, buildResultSummary(result),
					extractResultMap(result));
			return result;
		}
		catch (Throwable error) {
			LangfuseService.endNodeSpanError(nodeSpan, state, nodeName, error);
			throw error;
		}
	}

	private String buildResultSummary(Object result) {
		if (result == null) {
			return "";
		}
		return StringUtils.abbreviate(result.toString(), 500);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> extractResultMap(Object result) {
		return result instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

}
