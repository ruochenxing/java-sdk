/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * MCP 标准输入输出传输层的服务器实现，使用标准输入输出流进行通信。
 * 消息通过 stdin/stdout 以换行分隔的 JSON-RPC 消息形式交换，
 * 错误和调试信息发送到 stderr。
 *
 * @author Christian Tzolov
 * @deprecated This method will be removed in 0.9.0. Use
 * {@link io.modelcontextprotocol.server.transport.StdioServerTransportProvider} instead.
 */
@Deprecated
public class StdioServerTransport implements ServerMcpTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioServerTransport.class);

	// 入站消息接收器
	private final Sinks.Many<JSONRPCMessage> inboundSink;

	// 出站消息接收器
	private final Sinks.Many<JSONRPCMessage> outboundSink;

	// JSON 对象映射器
	private ObjectMapper objectMapper;

	/** 处理入站消息的调度器 */
	private Scheduler inboundScheduler;

	/** 处理出站消息的调度器 */
	private Scheduler outboundScheduler;

	// 关闭状态标志
	private volatile boolean isClosing = false;

	// 输入流
	private final InputStream inputStream;

	// 输出流
	private final OutputStream outputStream;

	// 入站就绪信号
	private final Sinks.One<Void> inboundReady = Sinks.one();

	// 出站就绪信号
	private final Sinks.One<Void> outboundReady = Sinks.one();

	/**
	 * 默认构造函数，使用系统标准输入输出流
	 */
	public StdioServerTransport() {
		this(new ObjectMapper());
	}

	/**
	 * 使用自定义 JSON 对象映射器的构造函数
	 * @param objectMapper JSON 对象映射器
	 */
	public StdioServerTransport(ObjectMapper objectMapper) {

		Assert.notNull(objectMapper, "The ObjectMapper can not be null");

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

		this.objectMapper = objectMapper;
		this.inputStream = System.in;
		this.outputStream = System.out;

		// Use bounded schedulers for better resource management
		this.inboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "inbound");
		this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "outbound");
	}

	/**
	 * 建立连接并设置消息处理器
	 * @param handler 消息处理器
	 * @return 表示连接操作的 Mono
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return Mono.<Void>fromRunnable(() -> {
			handleIncomingMessages(handler);

			// Start threads
			startInboundProcessing();
			startOutboundProcessing();
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * 处理入站消息
	 * @param inboundMessageHandler 入站消息处理器
	 */
	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundMessageHandler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message)
				.transform(inboundMessageHandler)
				.contextWrite(ctx -> ctx.put("observation", "myObservation")))
			.doOnTerminate(() -> {
				// The outbound processing will dispose its scheduler upon completion
				this.outboundSink.tryEmitComplete();
				this.inboundScheduler.dispose();
			})
			.subscribe();
	}

	/**
	 * 发送消息
	 * @param message 要发送的消息
	 * @return 表示发送操作的 Mono
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then(Mono.defer(() -> {
			if (this.outboundSink.tryEmitNext(message).isSuccess()) {
				return Mono.empty();
			}
			else {
				return Mono.error(new RuntimeException("Failed to enqueue message"));
			}
		}));
	}

	/**
	 * 启动入站消息处理
	 */
	private void startInboundProcessing() {
		this.inboundScheduler.schedule(() -> {
			inboundReady.tryEmitValue(null);
			BufferedReader reader = null;
			try {
				reader = new BufferedReader(new InputStreamReader(inputStream));
				while (!isClosing) {
					try {
						String line = reader.readLine();
						if (line == null || isClosing) {
							break;
						}

						logger.debug("Received JSON message: {}", line);

						try {
							JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, line);
							if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
								logIfNotClosing("Failed to enqueue message");
								break;
							}
						}
						catch (Exception e) {
							logIfNotClosing("Error processing inbound message", e);
							break;
						}
					}
					catch (IOException e) {
						logIfNotClosing("Error reading from stdin", e);
						break;
					}
				}
			}
			catch (Exception e) {
				logIfNotClosing("Error in inbound processing", e);
			}
			finally {
				isClosing = true;
				inboundSink.tryEmitComplete();
			}
		});
	}

	/**
	 * 启动出站消息处理
	 */
	private void startOutboundProcessing() {
		Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer = messages -> messages // @formatter:off
			 .doOnSubscribe(subscription -> outboundReady.tryEmitValue(null))
			 .publishOn(outboundScheduler)
			 .handle((message, sink) -> {
				 if (message != null && !isClosing) {
					 try {
						 String jsonMessage = objectMapper.writeValueAsString(message);
						 // Escape any embedded newlines in the JSON message as per spec
						 jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");

						 synchronized (outputStream) {
							 outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
							 outputStream.write("\n".getBytes(StandardCharsets.UTF_8));
							 outputStream.flush();
						 }
						 sink.next(message);
					 }
					 catch (IOException e) {
						 if (!isClosing) {
							 logger.error("Error writing message", e);
							 sink.error(new RuntimeException(e));
						 }
						 else {
							 logger.debug("Stream closed during shutdown", e);
						 }
					 }
				 }
				 else if (isClosing) {
					 sink.complete();
				 }
			 })
			 .doOnComplete(() -> {
				 isClosing = true;
				 outboundScheduler.dispose();
			 })
			 .doOnError(e -> {
				 if (!isClosing) {
					 logger.error("Error in outbound processing", e);
					 isClosing = true;
					 outboundScheduler.dispose();
				 }
			 })
			 .map(msg -> (JSONRPCMessage) msg);

			 outboundConsumer.apply(outboundSink.asFlux()).subscribe();
	 } // @formatter:on

	/**
	 * 优雅关闭传输层
	 * @return 表示关闭操作的 Mono
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.<Void>defer(() -> {
			isClosing = true;
			logger.debug("Initiating graceful shutdown");
			// Completing the inbound causes the outbound to be completed as well, so
			// we only close the inbound.
			inboundSink.tryEmitComplete();
			logger.debug("Graceful shutdown complete");
			return Mono.empty();
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * 将数据反序列化为指定类型
	 * @param data 要反序列化的数据
	 * @param typeRef 目标类型引用
	 * @return 反序列化后的对象
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	/**
	 * 如果未关闭则记录异常信息
	 * @param message 日志消息
	 * @param e 异常
	 */
	private void logIfNotClosing(String message, Exception e) {
		if (!this.isClosing) {
			logger.error(message, e);
		}
	}

	/**
	 * 如果未关闭则记录信息
	 * @param message 日志消息
	 */
	private void logIfNotClosing(String message) {
		if (!this.isClosing) {
			logger.error(message);
		}
	}

}
