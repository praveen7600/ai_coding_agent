package com.praveen.aicodingagent.config;

import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Backing thread pool for AgentOrchestrator.run(), which is @Async on this
 * bean by name ("agentTaskExecutor"). A task's loop can run for minutes
 * (multiple LLM round trips + sandbox exec each iteration) - running it on
 * the request thread would tie up an HTTP worker thread for the whole
 * duration and time out the client's connection for no reason, since the
 * client is expected to watch progress over the SSE stream, not the
 * response of the trigger call itself.
 *
 * A bounded pool (not @Async's default SimpleAsyncTaskExecutor, which
 * spawns an unbounded thread per task) - one Postgres connection and one
 * sandbox container per running task means an unbounded pool would just
 * shift the bottleneck to the connection pool or Docker instead of failing
 * predictably here.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "agentTaskExecutor")
    public Executor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("agent-orchestrator-");
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return agentTaskExecutor();
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // AgentOrchestrator.run() is void, so any exception that escapes it
        // (a bug in the loop itself, not a modeled failure - those are
        // caught inside run() and turned into a FAILED transition) has
        // nowhere else to go. Log it loudly rather than let Spring's
        // default handler swallow it quietly - a task stuck in RUNNING
        // forever with no FAILED transition and no log line is the worst
        // version of this failure mode to debug later.
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
